// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import androidx.core.content.FileProvider

/**
 * Distinct authority from HeliBoard's GestureFileProvider so both can coexist.
 * Authority is `${applicationId}.stickers`; see manifest.
 */
class StickerFileProvider : FileProvider()
