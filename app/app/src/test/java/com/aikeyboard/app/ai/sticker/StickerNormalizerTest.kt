// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the pure compress-quality search helper. The full Bitmap → WebP path
 * needs Robolectric / on-device validation; the search loop itself is testable
 * with a synthetic byte-size curve.
 */
class StickerNormalizerTest {

    @Test
    fun returnsHighestQualityWithinBudget() {
        // Larger quality → larger output. Budget = 100; q=80 returns 80 bytes (fits),
        // q=95 returns 110 bytes (over). The loop should converge on q ≈ 86 (last fitting).
        val measure: (Int) -> ByteArray = { q -> ByteArray(q + 10) }
        val out = StickerNormalizer.compressToTargetBytes(
            measure = measure,
            budget = 100,
            minQuality = 30,
            maxQuality = 95,
        )
        assertTrue(out.size <= 100, "Expected output to fit budget, got ${out.size}")
        // We don't pin an exact quality (binary search may converge on either side
        // by one step), but verify the search picked a reasonably high quality.
        assertTrue(out.size >= 85, "Expected near-budget size, got ${out.size}")
    }

    @Test
    fun returnsMinQualityWhenNothingFits() {
        // Every output is over budget. Helper must still return something — the
        // best-effort fallback is the min-quality output (last-resort path).
        val measure: (Int) -> ByteArray = { q -> ByteArray(q + 1000) }
        val out = StickerNormalizer.compressToTargetBytes(
            measure = measure,
            budget = 50,
            minQuality = 30,
            maxQuality = 95,
        )
        // Fallback returns measure(minQuality) → 30 + 1000 = 1030 bytes
        assertEquals(1030, out.size)
    }

    @Test
    fun fastPathSkipsSearchWhenMaxQualityFits() {
        // measure() is called twice in the fast-path: once at maxQuality, no search needed.
        var calls = 0
        val measure: (Int) -> ByteArray = { q ->
            calls += 1
            ByteArray(10) // always fits
        }
        val out = StickerNormalizer.compressToTargetBytes(
            measure = measure,
            budget = 100,
            minQuality = 30,
            maxQuality = 95,
        )
        assertEquals(10, out.size)
        assertEquals(1, calls, "Expected fast path: only the max-quality probe")
    }
}
