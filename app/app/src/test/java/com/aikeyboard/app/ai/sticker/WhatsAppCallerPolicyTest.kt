// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 12 §6 contract pin: ALLOWED_CALLERS must contain both WhatsApp's
 * consumer and business package identifiers. A future rename of this set
 * shouldn't silently drop WhatsApp Business — that would surface as a
 * SecurityException at the caller's first add-pack attempt with no obvious
 * diagnostic in release builds (the debug log added in §6 only fires in
 * debug builds).
 */
class WhatsAppCallerPolicyTest {

    @Test
    fun `ALLOWED_CALLERS contains WhatsApp consumer`() {
        assertTrue("com.whatsapp" in WhatsAppStickerContentProvider.ALLOWED_CALLERS)
    }

    @Test
    fun `ALLOWED_CALLERS contains WhatsApp business`() {
        assertTrue("com.whatsapp.w4b" in WhatsAppStickerContentProvider.ALLOWED_CALLERS)
    }
}
