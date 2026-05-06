// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui.stickers

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.aikeyboard.app.ai.sticker.StickerNormalizer
import com.aikeyboard.app.ai.sticker.StickerPack
import com.aikeyboard.app.ai.sticker.StickerStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

@Composable
fun StickerPackEditRoute(
    packId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val storage = remember { StickerStorage.getInstance(context) }
    val tick by storage.changes.collectAsState()
    val pack: StickerPack? by produceState<StickerPack?>(
        initialValue = storage.getManifest().packs.firstOrNull { it.id == packId },
        tick,
    ) {
        value = storage.getManifest().packs.firstOrNull { it.id == packId }
    }
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMPORTS_PER_BATCH)
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            uris.forEach { uri ->
                val stickerId = UUID.randomUUID().toString()
                val fileName = "sticker_$stickerId.webp"
                val file = storage.stickerFile(packId, fileName)
                file.parentFile?.mkdirs()
                val ok = StickerNormalizer.normalize(context.contentResolver, uri, file)
                if (ok) storage.addSticker(packId, stickerId, fileName)
            }
        }
    }

    val resolved = pack
    if (resolved == null) {
        // Pack was deleted out from under us (e.g., from another screen). Bail.
        onBack()
        return
    }

    StickerPackEditScreen(
        pack = resolved,
        packDir = File(context.filesDir, "stickers/packs/${resolved.id}"),
        onBack = onBack,
        onRename = { newName -> storage.renamePack(resolved.id, newName) },
        onDeletePack = {
            storage.deletePack(resolved.id)
            onBack()
        },
        onDeleteSticker = { stickerId -> storage.deleteSticker(resolved.id, stickerId) },
        onImport = {
            importLauncher.launch(
                PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        },
    )
}

private const val MAX_IMPORTS_PER_BATCH = 30
