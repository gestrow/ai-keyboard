// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.a11y

import android.content.Context
import android.content.Intent
import com.aikeyboard.app.a11y.AlwaysOnService

/**
 * Phase 8 indirection: lets `src/main/` settings code start/stop the
 * fdroid-only AlwaysOnService without naming it. The play impl no-ops.
 *
 * Service start uses startForegroundService(...) so the system gives us the
 * 5-second window to call startForeground() before crashing the process.
 * AlwaysOnService.onStartCommand handles both the initial start and any
 * subsequent intents (boot, tile click, settings toggle).
 */
object AlwaysOnProxy {
    fun start(context: Context) {
        val intent = Intent(context, AlwaysOnService::class.java)
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, AlwaysOnService::class.java)
        context.stopService(intent)
    }

    fun isSupported(): Boolean = true
}
