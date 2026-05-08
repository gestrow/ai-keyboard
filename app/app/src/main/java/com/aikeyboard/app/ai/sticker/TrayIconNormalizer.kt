// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.graphics.scale
import com.aikeyboard.app.latin.utils.Log
import java.io.ByteArrayOutputStream
import java.io.File

object TrayIconNormalizer {

    private const val TAG = "TrayIconNormalizer"
    private const val TARGET_PX = 96
    private const val TARGET_BYTES = 50 * 1024
    private const val MIN_PX = 32

    /**
     * Decode [src], normalize to 96×96 center-cropped PNG under 50KB, write to [dst].
     * Returns true on success. Failures log only structural metadata and return false.
     */
    fun normalize(resolver: ContentResolver, src: Uri, dst: File): Boolean {
        return try {
            val source = ImageDecoder.createSource(resolver, src)
            val raw = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
            val cropped = squareCrop(raw)
            if (cropped !== raw) raw.recycle()
            val bytes = encodeFitting(cropped, TARGET_PX, TARGET_BYTES)
            cropped.recycle()
            dst.parentFile?.mkdirs()
            dst.writeBytes(bytes)
            true
        } catch (t: Throwable) {
            // runCatching guards the log call so JVM unit tests where android.util.Log
            // is stubbed don't crash the host process under Stub! exceptions.
            runCatching {
                Log.w(TAG, "Tray normalize failed: ${t.javaClass.simpleName}")
            }
            false
        }
    }

    private fun squareCrop(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        if (side == src.width && side == src.height) return src
        return Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    }

    /**
     * PNG quality is lossless — the `quality` argument to Bitmap.compress(PNG, q, …)
     * is ignored. To fit a byte budget we instead binary-search the bitmap dimension
     * (downsampling the 96×96 to a smaller PNG when needed). 96×96 is small enough
     * that this rarely fires; the fast path returns the first attempt.
     */
    @Suppress("SameParameterValue")
    internal fun encodeFitting(bitmap: Bitmap, targetPx: Int, budget: Int): ByteArray =
        encodeFitting({ px: Int -> compressAtPx(bitmap, px) }, targetPx, budget)

    /** Pure helper: extracted for unit testing. */
    @JvmStatic
    internal fun encodeFitting(
        compress: (Int) -> ByteArray,
        targetPx: Int,
        budget: Int,
    ): ByteArray {
        val first = compress(targetPx)
        if (first.size <= budget) return first
        var px = targetPx - 8
        var best = first
        while (px >= MIN_PX) {
            val attempt = compress(px)
            if (attempt.size <= budget) return attempt
            best = attempt
            px -= 8
        }
        return best
    }

    private fun compressAtPx(bitmap: Bitmap, px: Int): ByteArray {
        val target = if (bitmap.width == px && bitmap.height == px) bitmap
                     else bitmap.scale(px, px, filter = true)
        val baos = ByteArrayOutputStream()
        @Suppress("DEPRECATION") // PNG format does not have a non-deprecated spelling.
        target.compress(Bitmap.CompressFormat.PNG, 100, baos)
        if (target !== bitmap) target.recycle()
        return baos.toByteArray()
    }
}
