// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import org.junit.Test
import kotlin.test.assertEquals

class AddToWhatsAppHelperTest {

    @Test
    fun statusFromInstalled_neither_returnsNone() {
        assertEquals(
            AddToWhatsAppHelper.WhatsAppStatus.NONE,
            AddToWhatsAppHelper.statusFromInstalled(emptyList()),
        )
    }

    @Test
    fun statusFromInstalled_consumerOnly_returnsConsumerOnly() {
        assertEquals(
            AddToWhatsAppHelper.WhatsAppStatus.CONSUMER_ONLY,
            AddToWhatsAppHelper.statusFromInstalled(listOf("com.whatsapp")),
        )
    }

    @Test
    fun statusFromInstalled_businessOnly_returnsBusinessOnly() {
        assertEquals(
            AddToWhatsAppHelper.WhatsAppStatus.BUSINESS_ONLY,
            AddToWhatsAppHelper.statusFromInstalled(listOf("com.whatsapp.w4b")),
        )
    }

    @Test
    fun statusFromInstalled_both_returnsBoth() {
        assertEquals(
            AddToWhatsAppHelper.WhatsAppStatus.BOTH,
            AddToWhatsAppHelper.statusFromInstalled(listOf("com.whatsapp", "com.whatsapp.w4b")),
        )
    }
}
