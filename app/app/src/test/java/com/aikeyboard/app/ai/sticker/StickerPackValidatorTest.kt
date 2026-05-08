// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StickerPackValidatorTest {

    private fun pack(
        id: String = "12345678-aaaa-bbbb-cccc-1234567890ab",
        name: String = "Pack",
        stickers: Int = 3,
        trayIconFile: String? = "tray.png",
        publisher: String = "Me",
    ): StickerPack = StickerPack(
        id = id,
        name = name,
        createdAt = 0L,
        stickers = (1..stickers).map { Sticker(id = "s$it", fileName = "sticker_s$it.webp") },
        trayIconFile = trayIconFile,
        publisher = publisher,
    )

    @Test
    fun emptyPack_flagsTooFewStickersAndMissingTrayAndPublisher() {
        val issues = StickerPackValidator.validate(
            pack(stickers = 0, trayIconFile = null, publisher = "")
        )
        assertContains(issues, StickerPackValidator.Issue.TOO_FEW_STICKERS)
        assertContains(issues, StickerPackValidator.Issue.MISSING_TRAY_ICON)
        assertContains(issues, StickerPackValidator.Issue.MISSING_PUBLISHER)
    }

    @Test
    fun fullValidPack_passes() {
        val issues = StickerPackValidator.validate(pack(stickers = 5))
        assertEquals(emptyList(), issues)
    }

    @Test
    fun thirtyOneStickers_flagsTooMany() {
        val issues = StickerPackValidator.validate(pack(stickers = 31))
        assertContains(issues, StickerPackValidator.Issue.TOO_MANY_STICKERS)
    }

    @Test
    fun blankPublisher_flagged() {
        val issues = StickerPackValidator.validate(pack(publisher = "   "))
        assertContains(issues, StickerPackValidator.Issue.MISSING_PUBLISHER)
    }

    @Test
    fun longName_flagged() {
        val issues = StickerPackValidator.validate(pack(name = "x".repeat(129)))
        assertContains(issues, StickerPackValidator.Issue.NAME_TOO_LONG)
    }

    @Test
    fun longPublisher_flagged() {
        val issues = StickerPackValidator.validate(pack(publisher = "x".repeat(129)))
        assertContains(issues, StickerPackValidator.Issue.PUBLISHER_TOO_LONG)
    }

    @Test
    fun nonAsciiIdentifier_flagged() {
        val issues = StickerPackValidator.validate(pack(id = "paçk!"))
        assertContains(issues, StickerPackValidator.Issue.IDENTIFIER_INVALID)
    }

    @Test
    fun multipleIssues_allReturned() {
        val issues = StickerPackValidator.validate(
            pack(
                stickers = 0,
                trayIconFile = null,
                publisher = "",
                name = "x".repeat(200),
            )
        )
        // Should include at least: TOO_FEW, MISSING_TRAY, MISSING_PUBLISHER, NAME_TOO_LONG.
        assertTrue(issues.size >= 4, "Expected ≥4 issues, got $issues")
        assertContains(issues, StickerPackValidator.Issue.TOO_FEW_STICKERS)
        assertContains(issues, StickerPackValidator.Issue.MISSING_TRAY_ICON)
        assertContains(issues, StickerPackValidator.Issue.MISSING_PUBLISHER)
        assertContains(issues, StickerPackValidator.Issue.NAME_TOO_LONG)
    }
}
