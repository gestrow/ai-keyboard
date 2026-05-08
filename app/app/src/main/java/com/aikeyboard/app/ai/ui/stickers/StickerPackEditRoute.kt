// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui.stickers

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.aikeyboard.app.ai.sticker.AddToWhatsAppHelper
import com.aikeyboard.app.ai.sticker.StickerNormalizer
import com.aikeyboard.app.ai.sticker.StickerPack
import com.aikeyboard.app.ai.sticker.StickerStorage
import com.aikeyboard.app.ai.sticker.TrayIconNormalizer
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
    // CRITICAL: keep the SYNCHRONOUS initial-value lookup. Switching it to `null`
    // would let the LaunchedEffect below fire on first composition (because
    // `tick` is non-zero from prior mutations on any non-fresh install) and pop
    // the user out before the screen ever renders for an existing pack.
    val pack: StickerPack? by produceState<StickerPack?>(
        initialValue = storage.getManifest().packs.firstOrNull { it.id == packId },
        tick,
    ) {
        value = storage.getManifest().packs.firstOrNull { it.id == packId }
    }
    val scope = rememberCoroutineScope()

    // Phase 9b carry-over #2 fix: when the pack disappears (delete-from-this-screen
    // path), pop back BEFORE the next recomposition can render a null pack. With
    // the synchronous initialValue above, `pack` is non-null on first composition
    // for any existing packId, so this fires only after a real delete.
    LaunchedEffect(pack) {
        if (pack == null) onBack()
    }

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

    val trayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val fileName = TRAY_FILE_NAME
                val file = storage.trayIconFile(packId, fileName)
                file.parentFile?.mkdirs()
                val ok = TrayIconNormalizer.normalize(context.contentResolver, uri, file)
                if (ok) storage.setTrayIcon(packId, fileName)
            }
        }
    }

    val whatsAppLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // No on-screen feedback for v1 — WhatsApp itself shows confirmation UI on
        // accept and an error on cancel. The user is back on this screen either way.
    }

    val packSnapshot = pack ?: return

    StickerPackEditScreen(
        pack = packSnapshot,
        packDir = File(context.filesDir, "stickers/packs/${packSnapshot.id}"),
        whatsAppStatus = AddToWhatsAppHelper.status(context),
        onBack = onBack,
        onRename = { newName -> storage.renamePack(packSnapshot.id, newName) },
        onDeletePack = {
            // The LaunchedEffect(pack) above pops back automatically once `pack`
            // observes the deletion through StateFlow recomposition.
            storage.deletePack(packSnapshot.id)
        },
        onDeleteSticker = { stickerId -> storage.deleteSticker(packSnapshot.id, stickerId) },
        onImport = {
            importLauncher.launch(
                PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        },
        onChooseTrayIcon = {
            trayLauncher.launch(
                PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        },
        onSetPublisher = { publisher -> storage.setPublisher(packSnapshot.id, publisher) },
        onSetStickerEmojis = { stickerId, emojis ->
            storage.setStickerEmojis(packSnapshot.id, stickerId, emojis)
        },
        onAddToWhatsApp = {
            val intent = AddToWhatsAppHelper.buildIntent(
                packId = packSnapshot.id,
                authority = "${context.packageName}.whatsapp.stickers",
                packName = packSnapshot.name,
            )
            whatsAppLauncher.launch(intent)
        },
    )
}

private const val MAX_IMPORTS_PER_BATCH = 30
private const val TRAY_FILE_NAME = "tray.png"
