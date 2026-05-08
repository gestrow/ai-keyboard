// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.aikeyboard.app.latin.BuildConfig
import java.io.File

/**
 * Phase 9b: WhatsApp sticker pack contract. WhatsApp does NOT use COMMIT_CONTENT;
 * it queries this ContentProvider directly from its own process to fetch the pack
 * manifest + sticker bytes. URI hierarchy:
 *
 *   content://${applicationId}.whatsapp.stickers/metadata
 *   content://${applicationId}.whatsapp.stickers/metadata/<pack-id>
 *   content://${applicationId}.whatsapp.stickers/stickers/<pack-id>
 *   content://${applicationId}.whatsapp.stickers/stickers_asset/<pack-id>/<filename>
 *
 * Privacy: query/openAssetFile reject any caller whose getCallingPackage() is not
 * in {com.whatsapp, com.whatsapp.w4b}. getType() is intentionally NOT gated — the
 * Android framework calls it from our own process (callingPackage == null) for
 * intent-resolution and content-type detection.
 */
class WhatsAppStickerContentProvider : ContentProvider() {

    private val authority by lazy { "${BuildConfig.APPLICATION_ID}.whatsapp.stickers" }

    private lateinit var matcher: UriMatcher
    private lateinit var storage: StickerStorage

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        storage = StickerStorage.getInstance(ctx)
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, "metadata", CODE_METADATA_ALL)
            addURI(authority, "metadata/*", CODE_METADATA_SINGLE)
            addURI(authority, "stickers/*", CODE_STICKERS_FOR_PACK)
            addURI(authority, "stickers_asset/*/*", CODE_STICKER_ASSET)
        }
        return true
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?,
    ): Cursor? {
        assertAllowedCaller()
        return when (matcher.match(uri)) {
            CODE_METADATA_ALL -> metadataCursor(storage.getManifest().packs)
            CODE_METADATA_SINGLE -> {
                val packId = uri.lastPathSegment ?: return null
                val pack = storage.getManifest().packs.firstOrNull { it.id == packId } ?: return null
                metadataCursor(listOf(pack))
            }
            CODE_STICKERS_FOR_PACK -> {
                val packId = uri.lastPathSegment ?: return null
                val pack = storage.getManifest().packs.firstOrNull { it.id == packId } ?: return null
                stickersCursor(pack)
            }
            else -> null
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        assertAllowedCaller()
        if (matcher.match(uri) != CODE_STICKER_ASSET) return null
        val segments = uri.pathSegments
        if (segments.size < 3) return null
        val packId = segments[1]
        val fileName = segments[2]
        // Defense in depth: reject path components that try to escape the pack dir.
        if (packId.contains('/') || fileName.contains('/') || fileName.contains("..")) return null
        val ctx = context ?: return null
        val file = File(File(ctx.filesDir, "stickers/packs/$packId"), fileName)
        if (!file.exists()) return null
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    /**
     * NOT gated by assertAllowedCaller — the framework calls this from our own
     * process (callingPackage == null) for intent-resolution and content-type
     * detection; gating it would crash internal Android calls. WhatsApp's
     * reference sample also leaves this method ungated.
     */
    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        CODE_METADATA_ALL -> "vnd.android.cursor.dir/vnd.$authority.metadata"
        CODE_METADATA_SINGLE -> "vnd.android.cursor.item/vnd.$authority.metadata"
        CODE_STICKERS_FOR_PACK -> "vnd.android.cursor.dir/vnd.$authority.stickers"
        CODE_STICKER_ASSET -> {
            // Asset URIs serve both stickers (.webp) and tray icons (.png); dispatch
            // on the file extension. WhatsApp inspects this MIME and will mis-decode
            // if we hardcode one type.
            if (uri.lastPathSegment?.endsWith(".png", ignoreCase = true) == true) "image/png"
            else "image/webp"
        }
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = 0

    // --- helpers ---

    private fun assertAllowedCaller() {
        val caller = callingPackage
        if (caller == null || caller !in ALLOWED_CALLERS) {
            throw SecurityException("Caller $caller not allowed")
        }
    }

    private fun metadataCursor(packs: List<StickerPack>): Cursor {
        val cursor = MatrixCursor(METADATA_COLUMNS)
        for (pack in packs) {
            cursor.addRow(metadataRow(pack))
        }
        return cursor
    }

    private fun stickersCursor(pack: StickerPack): Cursor {
        val cursor = MatrixCursor(STICKER_COLUMNS)
        for (row in stickerRows(pack)) {
            cursor.addRow(row)
        }
        return cursor
    }

    companion object {
        private const val CODE_METADATA_ALL = 1
        private const val CODE_METADATA_SINGLE = 2
        private const val CODE_STICKERS_FOR_PACK = 3
        private const val CODE_STICKER_ASSET = 4

        private val ALLOWED_CALLERS = setOf("com.whatsapp", "com.whatsapp.w4b")

        internal val METADATA_COLUMNS = arrayOf(
            "sticker_pack_identifier",
            "sticker_pack_name",
            "sticker_pack_publisher",
            "sticker_pack_icon",
            "android_play_store_link",
            "ios_app_store_link",
            "sticker_pack_publisher_email",
            "sticker_pack_publisher_website",
            "sticker_pack_privacy_policy_website",
            "sticker_pack_license_agreement_website",
            "image_data_version",
            "whatsapp_will_not_cache_stickers",
            "animated_sticker_pack",
            "sticker_pack_image_redirect_url",
            "sticker_pack_publisher_redirect_url",
        )

        internal val STICKER_COLUMNS = arrayOf(
            "sticker_file_name",
            "sticker_emoji",
            "sticker_accessibility_text",
        )

        /** JVM-pure helper: extracted for unit testing the row shape without
         *  needing Robolectric for the MatrixCursor wiring. */
        @JvmStatic
        internal fun metadataRow(pack: StickerPack): Array<Any?> = arrayOf(
            pack.id,
            pack.name,
            pack.publisher,
            pack.trayIconFile.orEmpty(),
            "", "", "", "", "", "",
            pack.imageDataVersion.toString(),
            if (pack.avoidCache) 1 else 0,
            0, // animated_sticker_pack — v1 hard-codes static
            "", "",
        )

        @JvmStatic
        internal fun stickerRows(pack: StickerPack): List<Array<Any?>> =
            pack.stickers.map { sticker ->
                val emojiCsv = sticker.emojis.joinToString(separator = ",")
                val a11yText = sticker.emojis.joinToString(separator = " ").ifEmpty { pack.name }
                arrayOf(sticker.fileName, emojiCsv, a11yText)
            }
    }
}
