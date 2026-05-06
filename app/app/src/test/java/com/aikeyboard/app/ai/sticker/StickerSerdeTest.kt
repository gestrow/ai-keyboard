// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the StickerManifest envelope. The on-disk schema is what we ship,
 * so verifying its round-trip and forward-compat behavior is sufficient.
 */
class StickerSerdeTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun manifest_roundTrips() {
        val original = StickerManifest(
            schemaVersion = 1,
            packs = listOf(
                StickerPack(
                    id = "pack-1",
                    name = "My Stickers",
                    createdAt = 1_700_000_000_000L,
                    stickers = listOf(
                        Sticker(id = "s1", fileName = "sticker_s1.webp"),
                        Sticker(id = "s2", fileName = "sticker_s2.webp", emojis = listOf("😀", "🎉")),
                    ),
                ),
                StickerPack(
                    id = "pack-2",
                    name = "Empty",
                    createdAt = 1_700_000_001_000L,
                ),
            ),
        )

        val encoded = json.encodeToString(StickerManifest.serializer(), original)
        val decoded = json.decodeFromString(StickerManifest.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun manifest_unknownFields_areIgnored() {
        // Phase 9b will add `trayIconFile` and `contentsJson` fields; older builds
        // must tolerate the new keys without crashing.
        val withFutureFields = """
            {
              "schemaVersion": 1,
              "futureField": "ignore me",
              "packs": [
                {
                  "id": "p1",
                  "name": "Test",
                  "createdAt": 1700000000000,
                  "trayIconFile": "tray.png",
                  "stickers": [
                    {"id": "s1", "fileName": "sticker_s1.webp", "newField": 42}
                  ]
                }
              ]
            }
        """.trimIndent()

        val decoded = json.decodeFromString(StickerManifest.serializer(), withFutureFields)
        assertEquals(1, decoded.packs.size)
        assertEquals("Test", decoded.packs[0].name)
        assertEquals(1, decoded.packs[0].stickers.size)
    }

    @Test
    fun pack_emptyEmojis_defaultRoundTrips() {
        val sticker = Sticker(id = "s1", fileName = "sticker_s1.webp")
        val encoded = json.encodeToString(Sticker.serializer(), sticker)
        val decoded = json.decodeFromString(Sticker.serializer(), encoded)
        assertEquals(sticker, decoded)
        assertTrue(decoded.emojis.isEmpty())
    }
}
