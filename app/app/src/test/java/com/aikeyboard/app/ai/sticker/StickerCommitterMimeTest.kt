// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the mime-type matcher. The full `EditorInfo`-taking entry
 * point in [StickerCommitter.insert] is exercised on-device; the matcher itself
 * is the part with non-trivial logic and is testable here.
 */
class StickerCommitterMimeTest {

    @Test
    fun nullContentMimeTypes_rejected() {
        assertFalse(StickerCommitter.acceptsWebp(null))
    }

    @Test
    fun emptyContentMimeTypes_rejected() {
        assertFalse(StickerCommitter.acceptsWebp(emptyArray()))
    }

    @Test
    fun explicitWebp_accepted() {
        assertTrue(StickerCommitter.acceptsWebp(arrayOf("image/webp")))
    }

    @Test
    fun imageStar_accepted() {
        assertTrue(StickerCommitter.acceptsWebp(arrayOf("image/*")))
    }

    @Test
    fun imagePngOnly_rejected() {
        assertFalse(StickerCommitter.acceptsWebp(arrayOf("image/png")))
    }

    @Test
    fun mixedListIncludingWebp_accepted() {
        assertTrue(StickerCommitter.acceptsWebp(arrayOf("image/png", "image/jpeg", "image/webp")))
    }

    @Test
    fun anyMimeType_accepted() {
        assertTrue(StickerCommitter.acceptsWebp(arrayOf("*/*")))
    }

    @Test
    fun trimmingAndCaseInsensitive() {
        assertTrue(StickerCommitter.acceptsWebp(arrayOf("  Image/WebP  ")))
    }
}
