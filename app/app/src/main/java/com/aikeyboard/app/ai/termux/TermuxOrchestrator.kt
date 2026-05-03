// SPDX-License-Identifier: GPL-3.0-only
// /data/data/com.termux/* are Termux's own filesystem paths, not ours, so
// the SdCardPath lint warning doesn't apply.
@file:SuppressLint("SdCardPath")

package com.aikeyboard.app.ai.termux

// Phase 5b: production wiring of `com.termux.RUN_COMMAND` IPC. Phase 4's
// TermuxValidationActivity proved the intent shape works; this class is the
// production form that the IME's setup wizard + status screen drive.
//
// Privacy: stdout/stderr from the script are surfaced through the UI but
// never logged at any level; CLI prompt text can leak into stderr.

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class TermuxOrchestrator private constructor(private val appContext: Context) {

    enum class Provider(val cliName: String) {
        CLAUDE("claude"),
        GEMINI("gemini"),
    }

    enum class Status {
        TERMUX_NOT_INSTALLED,
        TERMUX_INSTALLED_NOT_CONFIGURED,
        SCRIPT_NOT_DEPLOYED,
        BRIDGE_NOT_RUNNING,
        BRIDGE_RUNNING,
    }

    data class RunResult(val exitCode: Int, val stdout: String, val stderr: String)

    data class ProviderStatus(val id: String, val available: Boolean, val reason: String?)

    sealed interface HealthState {
        data object Polling : HealthState
        data class Healthy(val providers: List<ProviderStatus>, val uptimeSeconds: Long) : HealthState
        data class Unreachable(val attemptedAtMs: Long) : HealthState
    }

    // request-id → continuation. Each runCommand() call inserts one entry,
    // resumes it from the broadcast receiver when Termux returns, and the
    // receiver removes the entry. ConcurrentHashMap covers the race window
    // between broadcast delivery thread and the awaiting coroutine.
    private val pendingResults = ConcurrentHashMap<String, CancellableContinuation<RunResult>>()
    private val resultAction = "${appContext.packageName}.ai.termux.RUN_COMMAND_RESULT"

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
            val pending = pendingResults.remove(requestId) ?: return
            val bundle = intent.getBundleExtra("result") ?: intent.extras
            val stdout = bundle?.getString("stdout").orEmpty()
            val stderr = bundle?.getString("stderr").orEmpty()
            val exitCode = bundle?.getInt("exitCode", -1) ?: -1
            if (pending.isActive) {
                pending.resume(RunResult(exitCode, stdout, stderr))
            }
        }
    }

    init {
        // Application-scoped registration: orchestrator lives for the process'
        // lifetime, so we never need to unregister. RECEIVER_NOT_EXPORTED keeps
        // this broadcast off the system bus on API 33+.
        val filter = IntentFilter(resultAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }

    suspend fun detectStatus(): Status = withContext(Dispatchers.IO) {
        if (!isTermuxInstalled()) return@withContext Status.TERMUX_NOT_INSTALLED
        // The bridge is the most reliable signal: if /health responds, every
        // upstream prerequisite (allow-external-apps, script deploy, service
        // registration) must have happened. If unreachable we report
        // BRIDGE_NOT_RUNNING; the wizard's "Start the bridge" step then fires
        // setup.sh, and its exit code distinguishes "script missing" (127)
        // from "ran but failed to come up".
        if (probeHealthOnce() != null) Status.BRIDGE_RUNNING else Status.BRIDGE_NOT_RUNNING
    }

    fun launchFDroidTermuxPage() {
        val uri = "https://f-droid.org/packages/com.termux/".toUri()
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        appContext.startActivity(intent)
    }

    fun launchTermuxApp() {
        val intent = appContext.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
            ?: return
        intent.flags = intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
        appContext.startActivity(intent)
    }

    /**
     * Fires `bash $HOME/ai-keyboard-setup.sh <args>` via RUN_COMMAND.
     *
     * `foreground=true` opens Termux's terminal so the user can complete
     * interactive flows (OAuth browser handoff). `foreground=false` runs
     * detached — no UI; we only see the result via PendingIntent.
     *
     * Suspends until Termux returns the result or `timeoutMs` elapses, in
     * which case the returned RunResult has exitCode=-1.
     */
    suspend fun runSetupScript(
        args: List<String>,
        foreground: Boolean = true,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): RunResult {
        val argv = mutableListOf(SETUP_SCRIPT_PATH)
        argv.addAll(args)
        return runCommand(
            path = BASH_PATH,
            args = argv.toTypedArray(),
            background = !foreground,
            timeoutMs = timeoutMs,
        )
    }

    /**
     * Convenience: `setup.sh --reauth <provider>`. Forces foreground so the
     * user can complete the CLI's browser sign-in inside Termux.
     */
    suspend fun reauthProvider(provider: Provider): RunResult =
        runSetupScript(
            args = listOf("--reauth", provider.cliName),
            foreground = true,
            // OAuth flows can take a while if the user is slow on the browser.
            timeoutMs = REAUTH_TIMEOUT_MS,
        )

    /**
     * Stops the bridge service: `sv-disable ai-keyboard-bridge && sv down ai-keyboard-bridge`.
     * Background-only — there's nothing for the user to interact with.
     */
    suspend fun stopBridge(): RunResult = runCommand(
        path = BASH_PATH,
        args = arrayOf("-c", "sv-disable ai-keyboard-bridge && sv down ai-keyboard-bridge"),
        background = true,
        timeoutMs = STOP_TIMEOUT_MS,
    )

    /**
     * Cold flow that polls /health every second up to maxSeconds. Emits
     * Polling on subscribe, Unreachable per failure, and Healthy as soon as
     * the bridge responds (then completes). Cancellable via standard Flow
     * semantics — the wizard's `LaunchedEffect` cleans this up on screen exit.
     */
    fun observeBridgeHealth(maxSeconds: Int = 600): Flow<HealthState> = flow {
        emit(HealthState.Polling)
        var elapsed = 0
        while (elapsed < maxSeconds) {
            val healthy = probeHealthOnce()
            if (healthy != null) {
                emit(healthy)
                return@flow
            }
            emit(HealthState.Unreachable(System.currentTimeMillis()))
            delay(POLL_INTERVAL_MS)
            elapsed += 1
        }
    }.flowOn(Dispatchers.IO)

    /** Snapshot of /providers; null on bridge unreachable. */
    suspend fun fetchProviders(): List<ProviderStatus>? = withContext(Dispatchers.IO) {
        runCatching {
            val body = httpClient.get("$BRIDGE_BASE/providers").bodyAsText()
            parseProviderArray(parser.parseToJsonElement(body).jsonArray)
        }.getOrNull()
    }

    // ---- internals ----

    private fun isTermuxInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private suspend fun probeHealthOnce(): HealthState.Healthy? = withContext(Dispatchers.IO) {
        runCatching {
            val body = httpClient.get("$BRIDGE_BASE/health").bodyAsText()
            val obj = parser.parseToJsonElement(body).jsonObject
            val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull == true
            if (!ok) return@runCatching null
            val uptime = (obj["uptimeSeconds"] as? JsonPrimitive)?.longOrNull ?: 0L
            val providers = (obj["providers"] as? JsonArray)?.let { parseProviderArray(it) }.orEmpty()
            HealthState.Healthy(providers, uptime)
        }.getOrNull()
    }

    private fun parseProviderArray(arr: JsonArray): List<ProviderStatus> = arr.mapNotNull { el ->
        (el as? JsonObject)?.let { o ->
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@let null
            val available = (o["available"] as? JsonPrimitive)?.booleanOrNull ?: false
            val reason = (o["reason"] as? JsonPrimitive)?.contentOrNull
            ProviderStatus(id, available, reason)
        }
    }

    private suspend fun runCommand(
        path: String,
        args: Array<String>,
        background: Boolean,
        timeoutMs: Long,
    ): RunResult {
        val requestId = UUID.randomUUID().toString()
        val timeoutResult = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<RunResult> { cont ->
                pendingResults[requestId] = cont
                cont.invokeOnCancellation { pendingResults.remove(requestId) }
                try {
                    dispatchRunCommand(requestId, path, args, background)
                } catch (e: SecurityException) {
                    pendingResults.remove(requestId)
                    if (cont.isActive) cont.resume(RunResult(EXIT_NOT_PERMITTED, "", e.message.orEmpty()))
                } catch (e: Exception) {
                    pendingResults.remove(requestId)
                    if (cont.isActive) cont.resume(RunResult(EXIT_DISPATCH_FAILED, "", e.message.orEmpty()))
                }
            }
        }
        if (timeoutResult == null) {
            pendingResults.remove(requestId)
            return RunResult(EXIT_TIMEOUT, "", "RUN_COMMAND timed out after ${timeoutMs}ms")
        }
        return timeoutResult
    }

    private fun dispatchRunCommand(
        requestId: String,
        path: String,
        args: Array<String>,
        background: Boolean,
    ) {
        val resultIntent = Intent(resultAction).apply {
            setPackage(appContext.packageName)
            putExtra(EXTRA_REQUEST_ID, requestId)
        }
        // FLAG_MUTABLE so Termux can attach the stdout/stderr/exitCode bundle
        // when it fires the PendingIntent.
        val resultPi = PendingIntent.getBroadcast(
            appContext,
            requestId.hashCode(),
            resultIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", path)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            // Both extra names exist across Termux versions — pass both, harmless if ignored.
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", resultPi)
            putExtra("com.termux.RUN_COMMAND_RESULT_PENDING_INTENT", resultPi)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        // Android 12+ blocks startService when target app is in background;
        // RunCommandService is declared as a foreground service and Termux is
        // typically idle, so startForegroundService is the path that works
        // across versions (verified in Phase 4 on Pixel 6 Pro). minSdk=29 so
        // the API-26 floor for startForegroundService is always satisfied.
        appContext.startForegroundService(intent)
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            // Log the dispatch event (no args contents — they may include user-facing args).
            Log.d(TAG, "RUN_COMMAND dispatched (requestId=$requestId, foreground=${!background})")
        }
    }

    companion object {
        private const val TAG = "TermuxOrchestrator"
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"
        private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        private const val BASH_PATH = "$TERMUX_PREFIX/bin/bash"
        // Phase 5b convention — wizard's "deploy script" step instructs the user
        // to copy setup.sh to this exact location. Once there, every subsequent
        // action (install, reauth, restart, stop) fires through it.
        const val SETUP_SCRIPT_PATH = "$TERMUX_HOME/ai-keyboard-setup.sh"
        const val BRIDGE_BASE = "http://127.0.0.1:8787"

        private const val EXTRA_REQUEST_ID = "ai_keyboard_request_id"

        private const val POLL_INTERVAL_MS = 1_000L
        private const val DEFAULT_TIMEOUT_MS = 600_000L  // 10 min — covers OAuth + bridge startup
        private const val REAUTH_TIMEOUT_MS = 600_000L
        private const val STOP_TIMEOUT_MS = 30_000L

        // Synthetic exit codes for dispatch-side failures (Termux never returns these).
        const val EXIT_TIMEOUT = -1
        const val EXIT_NOT_PERMITTED = -2
        const val EXIT_DISPATCH_FAILED = -3

        private val parser = Json { ignoreUnknownKeys = true }

        // Local-loopback HTTP client. Short timeouts so /health probes fail
        // fast on the polling path. Separate from RemoteApiBackend.httpClient
        // (whose 60s socket timeout is sized for SSE streams against
        // api.anthropic.com / generativelanguage.googleapis.com).
        private val httpClient: HttpClient by lazy {
            HttpClient(OkHttp) {
                expectSuccess = false
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(HttpTimeout) {
                    connectTimeoutMillis = 2_000
                    socketTimeoutMillis = 2_000
                    requestTimeoutMillis = 2_000
                }
            }
        }

        @Volatile private var instance: TermuxOrchestrator? = null

        @JvmStatic
        fun getInstance(context: Context): TermuxOrchestrator =
            instance ?: synchronized(this) {
                instance ?: TermuxOrchestrator(context.applicationContext).also { instance = it }
            }
    }
}
