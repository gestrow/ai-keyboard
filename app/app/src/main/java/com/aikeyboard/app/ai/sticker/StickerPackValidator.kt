// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

object StickerPackValidator {

    enum class Issue {
        TOO_FEW_STICKERS,         // < 3
        TOO_MANY_STICKERS,        // > 30
        MISSING_TRAY_ICON,
        MISSING_PUBLISHER,
        NAME_TOO_LONG,
        PUBLISHER_TOO_LONG,
        IDENTIFIER_INVALID,
    }

    private const val MAX_NAME_LEN = 128
    private const val MAX_PUBLISHER_LEN = 128
    private const val MIN_STICKERS = 3
    private const val MAX_STICKERS = 30
    private val IDENTIFIER_REGEX = Regex("^[A-Za-z0-9_.\\-]+$")

    /** Returns all issues so the UI can render them simultaneously. Empty list = OK. */
    fun validate(pack: StickerPack): List<Issue> = buildList {
        if (pack.stickers.size < MIN_STICKERS) add(Issue.TOO_FEW_STICKERS)
        if (pack.stickers.size > MAX_STICKERS) add(Issue.TOO_MANY_STICKERS)
        if (pack.trayIconFile.isNullOrEmpty()) add(Issue.MISSING_TRAY_ICON)
        if (pack.publisher.isBlank()) add(Issue.MISSING_PUBLISHER)
        if (pack.name.length > MAX_NAME_LEN) add(Issue.NAME_TOO_LONG)
        if (pack.publisher.length > MAX_PUBLISHER_LEN) add(Issue.PUBLISHER_TOO_LONG)
        if (!IDENTIFIER_REGEX.matches(pack.id)) add(Issue.IDENTIFIER_INVALID)
    }
}
