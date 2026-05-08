// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the pure encode-search helper. The full Bitmap → PNG path needs
 * Robolectric / on-device validation; the fitting loop itself is testable
 * with a synthetic byte-size curve.
 */
class TrayIconNormalizerTest {

    @Test
    fun fastPath_firstAttemptUnderBudget_returnsImmediately() {
        var calls = 0
        val compress: (Int) -> ByteArray = { _ ->
            calls += 1
            ByteArray(10) // always fits
        }
        val out = TrayIconNormalizer.encodeFitting(compress, 96, 50_000)
        assertEquals(10, out.size)
        assertEquals(1, calls, "Expected fast path: only the target-px probe")
    }

    @Test
    fun shrinks_in8pxSteps_untilFits() {
        // Output size proportional to px squared. At 96 → 9216 (over budget 5000),
        // at 88 → 7744 (over), at 80 → 6400 (over), at 72 → 5184 (over),
        // at 64 → 4096 (fits). Should take exactly 5 calls.
        var calls = 0
        val compress: (Int) -> ByteArray = { px ->
            calls += 1
            ByteArray(px * px)
        }
        val out = TrayIconNormalizer.encodeFitting(compress, 96, 5_000)
        assertTrue(out.size <= 5_000, "Expected to fit budget, got ${out.size}")
        assertEquals(64 * 64, out.size)
        assertEquals(5, calls)
    }

    @Test
    fun honorsFloorWhenNothingFits() {
        // Every probe overflows. Walk: 96, 88, 80, 72, 64, 56, 48, 40, 32 — 9 calls,
        // returns the last probe (at MIN_PX=32) as best-effort fallback.
        var calls = 0
        val compress: (Int) -> ByteArray = { px ->
            calls += 1
            ByteArray(px * px * 100)
        }
        val out = TrayIconNormalizer.encodeFitting(compress, 96, 100)
        assertEquals(32 * 32 * 100, out.size)
        assertEquals(9, calls)
    }
}
