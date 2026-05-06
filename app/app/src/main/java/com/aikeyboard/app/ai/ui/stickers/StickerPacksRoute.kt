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
import androidx.compose.ui.res.stringResource
import com.aikeyboard.app.ai.sticker.StickerNormalizer
import com.aikeyboard.app.ai.sticker.StickerPack
import com.aikeyboard.app.ai.sticker.StickerStorage
import com.aikeyboard.app.latin.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun StickerPacksRoute(
    onBack: () -> Unit,
    onOpenPack: (packId: String) -> Unit,
) {
    val context = LocalContext.current
    val storage = remember { StickerStorage.getInstance(context) }
    // Cross-screen consistency: every storage mutation increments StickerStorage.changes;
    // both the list and the edit screen subscribe and re-derive their data on every emission.
    val tick by storage.changes.collectAsState()
    val packs by produceState(initialValue = storage.getManifest().packs, tick) {
        value = storage.getManifest().packs
    }
    val scope = rememberCoroutineScope()
    val defaultPackName = stringResource(R.string.ai_stickers_default_pack_name)

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMPORTS_PER_BATCH)
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val pack = storage.ensureDefaultPack(defaultPackName)
            uris.forEach { uri ->
                val stickerId = UUID.randomUUID().toString()
                val fileName = "sticker_$stickerId.webp"
                val file = storage.stickerFile(pack.id, fileName)
                val ok = StickerNormalizer.normalize(context.contentResolver, uri, file)
                if (ok) storage.addSticker(pack.id, stickerId, fileName)
            }
        }
    }

    StickerPacksScreen(
        packs = packs,
        onBack = onBack,
        onOpenPack = onOpenPack,
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
