// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.a11y

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aikeyboard.app.ai.a11y.AlwaysOnProxy
import com.aikeyboard.app.ai.storage.SecureStorage

/**
 * Phase 8 boot receiver, with Phase 12 diagnostic logging. If always-on
 * Read & Respond was on when the device shut down, restart the FGS after the
 * user unlocks (directBootAware="false" defers the receiver until then, which
 * is when Tink-backed SecureStorage becomes readable).
 *
 * Replaces Phase 8's runCatching with structured exception handling so the
 * §16.3 smoke can observe WHERE the boot-restart path is failing on Android
 * 14+. All log payloads are structural (action name, boolean, exception class)
 * — never t.message, never user content. Privacy invariant per
 * PHASE_REVIEW.md "no leaks".
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "onReceive action=${intent.action}")
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val enabled = try {
            SecureStorage.getInstance(appContext).isAlwaysOnEnabled()
        } catch (t: Throwable) {
            Log.w(TAG, "storage read failed: ${t.javaClass.simpleName}")
            return
        }
        Log.i(TAG, "alwaysOnEnabled=$enabled")
        if (!enabled) return
        try {
            AlwaysOnProxy.start(appContext)
            Log.i(TAG, "AlwaysOnProxy.start invoked")
        } catch (t: Throwable) {
            Log.w(TAG, "AlwaysOnProxy.start failed: ${t.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
