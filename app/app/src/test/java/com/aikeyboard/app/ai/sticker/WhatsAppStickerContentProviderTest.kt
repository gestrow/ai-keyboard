// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Phase 9b: row-shape tests via the JVM-pure metadataRow / stickerRows helpers,
 * matching Phase 9a's "no Robolectric" precedent. The URI matcher and the
 * calling-package gate are exercised on-device (PHASE_9b_PROMPT.md §17 scenarios
 * 4 and 5).
 */
class WhatsAppStickerContentProviderTest {

    private fun pack(
        id: String = "abc-123",
        name: String = "Pack",
        publisher: String = "Me",
        trayIconFile: String? = "tray.png",
        imageDataVersion: Long = 1L,
        avoidCache: Boolean = false,
        stickers: List<Sticker> = emptyList(),
    ): StickerPack = StickerPack(
        id = id,
        name = name,
        createdAt = 0L,
        stickers = stickers,
        trayIconFile = trayIconFile,
        publisher = publisher,
        imageDataVersion = imageDataVersion,
        avoidCache = avoidCache,
    )

    @Test
    fun metadataRow_emptyTrayIcon_serializesAsEmptyString() {
        val row = WhatsAppStickerContentProvider.metadataRow(pack(trayIconFile = null))
        // sticker_pack_icon is the 4th column.
        assertEquals("", row[3])
    }

    @Test
    fun metadataRow_imageDataVersion_serializesAsDecimalString() {
        val row = WhatsAppStickerContentProvider.metadataRow(pack(imageDataVersion = 42L))
        // image_data_version is the 11th column (index 10).
        assertEquals("42", row[10])
    }

    @Test
    fun metadataRow_avoidCacheTrue_serializesAsOne() {
        val rowTrue = WhatsAppStickerContentProvider.metadataRow(pack(avoidCache = true))
        val rowFalse = WhatsAppStickerContentProvider.metadataRow(pack(avoidCache = false))
        // whatsapp_will_not_cache_stickers is the 12th column (index 11).
        assertEquals(1, rowTrue[11])
        assertEquals(0, rowFalse[11])
    }

    @Test
    fun metadataRow_columnCount_matchesSchema() {
        val row = WhatsAppStickerContentProvider.metadataRow(pack())
        assertEquals(WhatsAppStickerContentProvider.METADATA_COLUMNS.size, row.size)
    }

    @Test
    fun stickerRow_emptyEmojis_a11yTextFallsBackToPackName() {
        val rows = WhatsAppStickerContentProvider.stickerRows(
            pack(name = "MyPack", stickers = listOf(Sticker(id = "s1", fileName = "f.webp")))
        )
        assertEquals(1, rows.size)
        // sticker_emoji blank, accessibility_text falls back to pack name.
        assertEquals("", rows[0][1])
        assertEquals("MyPack", rows[0][2])
    }

    @Test
    fun stickerRow_multipleEmojis_csvCommaSeparated() {
        val rows = WhatsAppStickerContentProvider.stickerRows(
            pack(stickers = listOf(
                Sticker(id = "s1", fileName = "f.webp", emojis = listOf("🎉", "👋"))
            ))
        )
        assertEquals("🎉,👋", rows[0][1])
    }

    @Test
    fun stickerRow_a11yText_spaceSeparated() {
        val rows = WhatsAppStickerContentProvider.stickerRows(
            pack(stickers = listOf(
                Sticker(id = "s1", fileName = "f.webp", emojis = listOf("🎉", "👋"))
            ))
        )
        assertEquals("🎉 👋", rows[0][2])
    }
}
