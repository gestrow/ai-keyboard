// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import kotlinx.serialization.Serializable

@Serializable
data class StickerManifest(
    val schemaVersion: Int = 1,
    val packs: List<StickerPack> = emptyList(),
)

@Serializable
data class StickerPack(
    val id: String,
    val name: String,
    val createdAt: Long,
    val stickers: List<Sticker> = emptyList(),
)

@Serializable
data class Sticker(
    val id: String,
    /**
     * Filename relative to the pack directory. Always *.webp; the file
     * itself is the canonicalized 512×512 normalized output.
     */
    val fileName: String,
    /**
     * Optional emoji tags. Phase 9a does not surface a UI to edit these
     * (it's a Phase 9b deliverable for WhatsApp's contents.json), so
     * imports default to empty.
     */
    val emojis: List<String> = emptyList(),
)
