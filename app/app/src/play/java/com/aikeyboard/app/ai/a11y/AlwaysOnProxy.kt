// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.a11y

import android.content.Context

/**
 * Phase 8 play-flavor stub. The play APK ships no foreground service, no
 * tile service, no boot receiver. Settings UI uses isSupported() to gray out
 * the toggle and surface "not supported in Play build" copy.
 */
object AlwaysOnProxy {
    @Suppress("UNUSED_PARAMETER")
    fun start(context: Context) { /* no-op */ }

    @Suppress("UNUSED_PARAMETER")
    fun stop(context: Context) { /* no-op */ }

    fun isSupported(): Boolean = false
}
