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
    /** Phase 9b: filename relative to the pack directory of the 96×96 PNG tray icon.
     *  Null until the user chooses one in pack-edit. WhatsApp requires a tray icon. */
    val trayIconFile: String? = null,
    /** Phase 9b: pack publisher shown in WhatsApp's pack list. Required by WhatsApp. */
    val publisher: String = "",
    /** Phase 9b: monotonic version per pack. Bumped by StickerStorage on every
     *  mutation that touches the pack (rename / sticker add+delete+edit / tray /
     *  publisher). WhatsApp re-fetches a pack whenever this differs from its cache. */
    val imageDataVersion: Long = 1L,
    /** Phase 9b: WhatsApp's "do not cache" hint. Default false; v1 doesn't surface a UI. */
    val avoidCache: Boolean = false,
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
