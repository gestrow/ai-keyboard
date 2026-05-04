// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.a11y

import com.aikeyboard.app.a11y.ScreenReaderService

/**
 * Phase 7b indirection: routes Read & Respond's screen-walk request from
 * `src/main/` code to Phase 7a's `ScreenReaderService` without leaking the
 * fdroid-only class name into shared code.
 *
 * The play flavor ships a no-op variant of this object that returns
 * BUILD_DOES_NOT_SUPPORT; the dex layer thus never references
 * ScreenReaderService in play APKs.
 */
object A11yProxy {
    fun requestScreenContext(): ScreenReaderResult {
        val service = ScreenReaderService.instance
            ?: return ScreenReaderResult.Failure.SERVICE_NOT_ENABLED
        return service.requestScreenContext()
    }
}
