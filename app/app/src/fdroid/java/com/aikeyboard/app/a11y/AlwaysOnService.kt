// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.a11y

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.aikeyboard.app.ai.a11y.A11yProxy
import com.aikeyboard.app.ai.a11y.ReadRespondNotificationBuilder
import com.aikeyboard.app.ai.a11y.ReadRespondPromptBuilder
import com.aikeyboard.app.ai.a11y.ScreenReaderResult
import com.aikeyboard.app.ai.client.AiStreamEvent
import com.aikeyboard.app.ai.client.BackendResolver
import com.aikeyboard.app.ai.storage.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Phase 8 foreground service. Runs whenever Always-On Read & Respond is
 * toggled on in AI Settings. Owns the persistent FGS-chip notification (so
 * users can see what's running) and posts streaming-response notifications
 * when triggered by tile or notification action.
 *
 * Does NOT extend AccessibilityService — that remains a separate concern in
 * ScreenReaderService. AlwaysOnService is started/stopped by user toggle and
 * orchestrates tile/notification interactions; it calls A11yProxy whenever
 * it needs to walk the screen.
 */
class AlwaysOnService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var inFlight: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ReadRespondNotificationBuilder.createChannels(this)
    }

    @SuppressLint("InlinedApi")
    // FOREGROUND_SERVICE_TYPE_SPECIAL_USE is API 34+, but minSdk is 29.
    // Older API levels (29-33) treat the bitmask value as benign extra type
    // bits and ignore it; the manifest's foregroundServiceType="specialUse"
    // is similarly ignored on <34. No runtime crash on the supported range.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = ReadRespondNotificationBuilder.buildPersistentChip(this)
        // Android 14+ requires specifying the FGS type at startForeground time;
        // the type must match what's declared in the manifest. The 3-arg overload
        // exists since API 29.
        startForeground(
            ReadRespondNotificationBuilder.NOTIF_ID_PERSISTENT,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        when (intent?.action) {
            ACTION_READ_RESPOND -> triggerReadRespond()
            // null action = service start from toggle/boot; chip is already
            // posted, nothing else to do.
            else -> {}
        }
        // START_STICKY: if the system kills us under memory pressure, restart
        // with a null intent. The chip will re-post; no in-flight requests
        // resume (acceptable — user re-triggers).
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        inFlight = null
        super.onDestroy()
    }

    /**
     * Tile click or notification-action invocation. Walks the screen, builds
     * the prompt, streams into a transient notification. Cancels any
     * in-flight request before starting a new one.
     */
    private fun triggerReadRespond() {
        inFlight?.cancel()
        val storage = SecureStorage.getInstance(applicationContext)
        if (!storage.isReadRespondConsented()) {
            ReadRespondNotificationBuilder.postConsentRequired(this)
            return
        }
        val walk = A11yProxy.requestScreenContext()
        when (walk) {
            ScreenReaderResult.Failure.SERVICE_NOT_ENABLED -> {
                ReadRespondNotificationBuilder.postServiceNotEnabled(this)
                return
            }
            ScreenReaderResult.Failure.NO_ACTIVE_WINDOW -> {
                ReadRespondNotificationBuilder.postFailureChip(
                    this, ReadRespondNotificationBuilder.R_FAILURE_NO_WINDOW,
                )
                return
            }
            ScreenReaderResult.Failure.UNKNOWN_FAILURE -> {
                ReadRespondNotificationBuilder.postFailureChip(
                    this, ReadRespondNotificationBuilder.R_FAILURE_GENERIC,
                )
                return
            }
            ScreenReaderResult.Failure.BUILD_DOES_NOT_SUPPORT -> {
                // Defensive: unreachable, since AlwaysOnService is fdroid-only
                // and the proxy in fdroid never returns this enum value.
                Log.e(TAG, "AlwaysOnService got BUILD_DOES_NOT_SUPPORT from fdroid A11yProxy — bug")
                return
            }
            is ScreenReaderResult.Success -> { /* fall through */ }
        }
        val ctx = (walk as ScreenReaderResult.Success).context
        if (ctx.aboveInputText.isBlank()) {
            ReadRespondNotificationBuilder.postFailureChip(
                this, ReadRespondNotificationBuilder.R_FAILURE_NO_CONTEXT,
            )
            return
        }
        val backend = BackendResolver.resolve(storage)
        if (backend == null) {
            ReadRespondNotificationBuilder.postFailureChip(
                this, ReadRespondNotificationBuilder.R_FAILURE_NO_BACKEND,
            )
            return
        }
        val persona = storage.getPersonas()
            .firstOrNull { it.id == storage.getActivePersonaId() }
            ?: storage.getPersonas().first()
        val (input, systemPrompt) = ReadRespondPromptBuilder.build(
            aboveInputText = ctx.aboveInputText,
            focusedInputText = ctx.focusedInputText,
            persona = persona,
        )
        inFlight = scope.launch {
            val accumulated = StringBuilder()
            try {
                backend.rewrite(input, systemPrompt, fewShots = emptyList())
                    .collect { event ->
                        when (event) {
                            is AiStreamEvent.Delta -> {
                                accumulated.append(event.text)
                                ReadRespondNotificationBuilder.postStreaming(
                                    this@AlwaysOnService,
                                    accumulated.toString(),
                                )
                            }
                            AiStreamEvent.Done -> {
                                ReadRespondNotificationBuilder.postCompleted(
                                    this@AlwaysOnService,
                                    accumulated.toString(),
                                )
                            }
                            is AiStreamEvent.Error -> {
                                ReadRespondNotificationBuilder.postFailureChip(
                                    this@AlwaysOnService,
                                    ReadRespondNotificationBuilder.R_FAILURE_STREAM,
                                )
                            }
                        }
                    }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    Log.w(TAG, "AlwaysOn stream failed: ${t.javaClass.simpleName}")
                    ReadRespondNotificationBuilder.postFailureChip(
                        this@AlwaysOnService, ReadRespondNotificationBuilder.R_FAILURE_STREAM,
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "AlwaysOnService"
        const val ACTION_READ_RESPOND = "com.aikeyboard.app.ACTION_READ_RESPOND"
        // NOTE: notification ID constants and R_FAILURE_* sentinels live on
        // ReadRespondNotificationBuilder (in src/main/), NOT here. AlwaysOnService
        // is fdroid-only; the builder is in main. Putting the constants in main
        // is the only way for a main-residing class (the builder) to reference
        // them without a forbidden main → fdroid compile dependency.
    }
}
