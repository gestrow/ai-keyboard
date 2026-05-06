// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.File

object StickerNormalizer {

    private const val TAG = "StickerNormalizer"
    private const val TARGET_PX = 512
    private const val TARGET_BYTES = 100 * 1024
    private const val MIN_QUALITY = 30
    private const val MAX_QUALITY = 95

    /**
     * Decode [src], normalize to 512×512 center-cropped WebP under 100KB, write to [dst].
     * Returns true on success. Does NOT throw — failures log only structural metadata
     * (no URI strings, no byte counts) and return false.
     */
    fun normalize(resolver: ContentResolver, src: Uri, dst: File): Boolean {
        return try {
            val source = ImageDecoder.createSource(resolver, src)
            val raw = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
            val normalized = squareCropAndScale(raw, TARGET_PX)
            if (normalized !== raw) raw.recycle()
            val bytes = compressToTargetBytes(
                measure = { quality -> compressBitmap(normalized, quality) },
                budget = TARGET_BYTES,
                minQuality = MIN_QUALITY,
                maxQuality = MAX_QUALITY,
            )
            normalized.recycle()
            dst.parentFile?.mkdirs()
            dst.writeBytes(bytes)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Normalize failed: ${t.javaClass.simpleName}")
            false
        }
    }

    private fun squareCropAndScale(src: Bitmap, target: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val xOff = (src.width - side) / 2
        val yOff = (src.height - side) / 2
        val cropped = Bitmap.createBitmap(src, xOff, yOff, side, side)
        if (side == target) return cropped
        val scaled = cropped.scale(target, target)
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }

    private fun compressBitmap(bitmap: Bitmap, quality: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        // CompressFormat.WEBP_LOSSY is API 30+; we still target 29 for the lower bound.
        // The deprecated WEBP enum maps to lossy on every supported API level.
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        bitmap.compress(format, quality, baos)
        return baos.toByteArray()
    }

    /**
     * Pure helper: extracted for unit testing. Walks compress quality with a
     * binary search to find the largest quality whose output fits the byte budget.
     * Returns the bytes; caller writes them.
     */
    @JvmStatic
    internal fun compressToTargetBytes(
        measure: (Int) -> ByteArray,
        budget: Int,
        minQuality: Int,
        maxQuality: Int,
    ): ByteArray {
        // Fast path: max-quality output already fits.
        val maxBytes = measure(maxQuality)
        if (maxBytes.size <= budget) return maxBytes
        var lo = minQuality
        var hi = maxQuality
        var best: ByteArray = measure(minQuality)
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val candidate = measure(mid)
            if (candidate.size <= budget) {
                best = candidate
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }
}
