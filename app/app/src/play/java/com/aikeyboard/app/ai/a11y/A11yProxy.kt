// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.a11y

/**
 * Phase 7b play-flavor stub. The play APK does not ship an
 * AccessibilityService (see ARCHITECTURE.md Phase 7a row), so all Read &
 * Respond invocations short-circuit here. Returning a typed Failure rather
 * than throwing means the caller's `when` can render a toast and exit
 * cleanly — no crash, no log of internal class names.
 */
object A11yProxy {
    fun requestScreenContext(): ScreenReaderResult =
        ScreenReaderResult.Failure.BUILD_DOES_NOT_SUPPORT
}
