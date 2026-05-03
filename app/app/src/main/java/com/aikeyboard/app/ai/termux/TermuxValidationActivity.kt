// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.termux

// Phase 4 RUN_COMMAND validation harness. Debug-only — the manifest entry and
// `com.termux.permission.RUN_COMMAND` live in `src/debug/AndroidManifest.xml`,
// so this activity is unreachable in release builds.
//
// Background: Termux's RUN_COMMAND IPC has had policy turbulence across Termux
// versions and Android background-execution restrictions (see
// ARCHITECTURE.md "Reliability caveat"). Phase 5 builds the IME's
// TermuxOrchestrator on top of RUN_COMMAND, so we want a tight standalone
// reproducer first. If this activity can fire `echo` and observe its stdout,
// the orchestrator design is safe to proceed; if not, we pivot to one of the
// fallback IPC mechanisms documented in ARCHITECTURE.md before Phase 5a.

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.aikeyboard.app.latin.R

// Debug-only diagnostic harness — translatability and /data path lint warnings
// don't apply: the strings are dev breadcrumbs, and the /data paths are the
// Termux binary locations (we're invoking Termux's binaries, not our own).
@SuppressLint("SetTextI18n", "SdCardPath")
class TermuxValidationActivity : ComponentActivity() {

    private lateinit var statusView: TextView
    private lateinit var detailsView: TextView

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handleResult(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())

        val filter = IntentFilter(RESULT_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resultReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(resultReceiver, filter)
        }
        statusView.text = getString(R.string.termux_validation_idle)
        detailsView.text = ""
    }

    override fun onDestroy() {
        try { unregisterReceiver(resultReceiver) } catch (_: IllegalArgumentException) { /* not registered */ }
        super.onDestroy()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 96)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        statusView = TextView(this).apply {
            textSize = 18f
            setPadding(0, 0, 0, 32)
        }
        detailsView = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            movementMethod = ScrollingMovementMethod()
        }
        val sendButton = Button(this).apply {
            text = getString(R.string.termux_validation_send_echo)
            setOnClickListener { sendEchoIntent() }
        }
        val sleepButton = Button(this).apply {
            text = getString(R.string.termux_validation_send_sleep)
            setOnClickListener { sendSleepIntent() }
        }
        // Optional third button: run the bridge smoke script via RUN_COMMAND.
        // Only shown when a script has been pre-staged at the canonical path
        // (Phase 4 dev workflow: `adb push bridge-up-curl.sh
        // /data/local/tmp/bridge-up-curl.sh`). Result lands in detailsView via
        // the same PendingIntent path as echo/sleep.
        val smokeButton = Button(this).apply {
            text = "Run staged script (/data/local/tmp/bridge-up-curl.sh)"
            setOnClickListener { sendStagedScriptIntent() }
        }
        root.addView(statusView)
        root.addView(sendButton)
        root.addView(sleepButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 })
        root.addView(smokeButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16; bottomMargin = 32 })
        val scroll = ScrollView(this).apply {
            addView(detailsView)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(scroll)
        // Footer hint kept as plain text rather than a string resource — it's
        // a one-time debug breadcrumb, not user-facing copy.
        val footer = TextView(this).apply {
            text = "Requires Termux + `allow-external-apps=true` in ~/.termux/termux.properties"
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }
        root.addView(footer)
        return root
    }

    private fun sendEchoIntent() {
        val token = "termux ipc ok at ${SystemClock.elapsedRealtime()}"
        send(
            label = getString(R.string.termux_validation_sending_echo),
            path = "/data/data/com.termux/files/usr/bin/echo",
            args = arrayOf(token),
            background = true,
            expectedToken = token,
        )
    }

    private fun sendStagedScriptIntent() {
        // Empty expected token — we don't validate stdout here, we just want
        // it dumped into detailsView. (Could match against /sdcard staging.)
        send(
            label = "Running /data/local/tmp/bridge-up-curl.sh…",
            path = "/data/data/com.termux/files/usr/bin/bash",
            args = arrayOf("/data/local/tmp/bridge-up-curl.sh"),
            background = true,
            expectedToken = "=== END ===",
        )
    }

    private fun sendSleepIntent() {
        val token = "termux sleep ok ${SystemClock.elapsedRealtime()}"
        // sh -c "sleep 3 && echo <token>" — exercises the long-running path
        // (Termux must keep the process tracked while we await the result).
        send(
            label = getString(R.string.termux_validation_sending_sleep),
            path = "/data/data/com.termux/files/usr/bin/sh",
            args = arrayOf("-c", "sleep 3 && echo \"$token\""),
            background = true,
            expectedToken = token,
        )
    }

    private fun send(label: String, path: String, args: Array<String>, background: Boolean, expectedToken: String) {
        statusView.text = label
        detailsView.text = "PATH=$path\nARGS=${args.joinToString(" ")}\n"

        val resultIntent = Intent(RESULT_ACTION).apply {
            setPackage(packageName)
            putExtra(EXTRA_EXPECTED_TOKEN, expectedToken)
        }
        // FLAG_MUTABLE is required so Termux can attach the result Bundle.
        val resultPi = PendingIntent.getBroadcast(
            this,
            REQUEST_CODE,
            resultIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val intent = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", path)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            // Some Termux versions also recognize this synonym; harmless if ignored.
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", resultPi)
            putExtra("com.termux.RUN_COMMAND_RESULT_PENDING_INTENT", resultPi)
        }
        try {
            // Android 12+ blocks startService on background apps; RunCommandService
            // is declared as a foreground service and Termux is usually idle, so
            // startForegroundService is the path that works across versions.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            appendDetail("Intent dispatched to com.termux/.app.RunCommandService\n")
        } catch (e: SecurityException) {
            statusView.text = "❌ SecurityException — is com.termux.permission.RUN_COMMAND granted?"
            appendDetail("error: ${e.message}\n")
        } catch (e: Exception) {
            statusView.text = "❌ ${e.javaClass.simpleName} — Termux not installed?"
            appendDetail("error: ${e.message}\n")
        }
    }

    private fun handleResult(intent: Intent) {
        // Termux nests result extras inside Bundles named by RunCommandService.
        val bundle = intent.getBundleExtra("result") ?: intent.extras
        val stdout = bundle?.getString("stdout") ?: ""
        val stderr = bundle?.getString("stderr") ?: ""
        val exitCode = bundle?.getInt("exitCode", -1) ?: -1
        val expected = intent.getStringExtra(EXTRA_EXPECTED_TOKEN) ?: ""
        val ok = stdout.contains(expected) && exitCode == 0

        statusView.text = if (ok) {
            "✅ Termux executed; stdout matches"
        } else {
            "❌ Termux returned (exit=$exitCode) but stdout did not match"
        }
        appendDetail(buildString {
            append("--- result ---\n")
            append("exitCode=$exitCode\n")
            append("stdout=").append(stdout).append('\n')
            if (stderr.isNotEmpty()) append("stderr=").append(stderr).append('\n')
            append("expected=$expected\n")
        })
    }

    private fun appendDetail(text: String) {
        detailsView.append(text)
    }

    companion object {
        // Broadcast action used to deliver the PendingIntent result back into
        // this Activity. Scoped to this package so Termux's broadcast cannot be
        // intercepted by other apps.
        private const val RESULT_ACTION = "com.aikeyboard.app.debug.TERMUX_RUN_COMMAND_RESULT"
        private const val EXTRA_EXPECTED_TOKEN = "expected_token"
        private const val REQUEST_CODE = 100
    }
}
