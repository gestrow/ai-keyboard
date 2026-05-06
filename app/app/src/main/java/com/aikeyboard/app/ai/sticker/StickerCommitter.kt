// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File

object StickerCommitter {

    private const val MIME_WEBP = "image/webp"

    enum class Result { OK, NO_CONNECTION, UNSUPPORTED_FIELD, FAILED }

    /**
     * @return [Result.OK] if the target field accepted the sticker;
     *  [Result.UNSUPPORTED_FIELD] if `contentMimeTypes` declared no compatible type
     *  (commitContent never called); [Result.NO_CONNECTION] if the IME has no current
     *  input connection or no [EditorInfo]; [Result.FAILED] if commitContent returned false.
     */
    fun insert(
        context: Context,
        ic: InputConnection?,
        editorInfo: EditorInfo?,
        stickerFile: File,
        authority: String,
    ): Result {
        if (ic == null || editorInfo == null) return Result.NO_CONNECTION
        if (!fieldAcceptsWebp(editorInfo)) return Result.UNSUPPORTED_FIELD
        val uri: Uri = FileProvider.getUriForFile(context, authority, stickerFile)
        val info = InputContentInfoCompat(
            uri,
            ClipDescription(stickerFile.name, arrayOf(MIME_WEBP)),
            null, // linkUri — not applicable for a local sticker
        )
        // minSdk 29 — N_MR1 (25) is always satisfied; the flag is unconditionally set.
        val flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
        val ok = InputConnectionCompat.commitContent(ic, editorInfo, info, flags, null)
        return if (ok) Result.OK else Result.FAILED
    }

    private fun fieldAcceptsWebp(editorInfo: EditorInfo): Boolean =
        acceptsWebp(editorInfo.contentMimeTypes)

    /**
     * Pure helper: extracted for unit testing. Implements the same wildcard
     * semantics as [ClipDescription.compareMimeTypes] for the subset we care
     * about ("image/webp" and the "image/" + "*" wildcard), without taking an
     * Android dependency at the JVM-test seam.
     */
    @JvmStatic
    internal fun acceptsWebp(declaredMimes: Array<String>?): Boolean {
        if (declaredMimes == null) return false
        return declaredMimes.any { declared ->
            val d = declared.trim().lowercase()
            d == MIME_WEBP || d == "image/*" || d == "*/*"
        }
    }
}
