// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui.stickers

import android.graphics.BitmapFactory
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.aikeyboard.app.ai.sticker.Sticker
import com.aikeyboard.app.ai.sticker.StickerPack
import com.aikeyboard.app.latin.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerPackEditScreen(
    pack: StickerPack,
    packDir: File,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onDeletePack: () -> Unit,
    onDeleteSticker: (String) -> Unit,
    onImport: () -> Unit,
) {
    var name by remember(pack.id) { mutableStateOf(pack.name) }
    var pendingDeleteSticker by remember { mutableStateOf<String?>(null) }
    var pendingDeletePack by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_stickers_pack_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        // Save name on back-out if it changed and isn't blank.
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty() && trimmed != pack.name) {
                            onRename(trimmed)
                        }
                        onBack()
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.ai_settings_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onImport) {
                        Icon(
                            painter = painterResource(R.drawable.ic_plus),
                            contentDescription = stringResource(R.string.ai_stickers_pack_import_more),
                        )
                    }
                },
            )
        }
    ) { padding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.ai_stickers_pack_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) onRename(trimmed)
                },
                enabled = name.trim().isNotEmpty() && name.trim() != pack.name,
            ) {
                Text(stringResource(R.string.ai_stickers_pack_save))
            }

            HorizontalDivider()

            if (pack.stickers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.ai_stickers_pack_empty))
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pack.stickers, key = { it.id }) { sticker ->
                        StickerCell(
                            sticker = sticker,
                            file = File(packDir, sticker.fileName),
                            onLongPress = { pendingDeleteSticker = sticker.id },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()

            TextButton(onClick = { pendingDeletePack = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_ai_delete),
                    contentDescription = null,
                )
                Spacer(Modifier.padding(4.dp))
                Text(stringResource(R.string.ai_stickers_pack_delete))
            }
        }
    }

    pendingDeleteSticker?.let { stickerId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSticker = null },
            title = { Text(stringResource(R.string.ai_stickers_sticker_delete_title)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSticker(stickerId)
                    pendingDeleteSticker = null
                }) {
                    Text(stringResource(R.string.ai_stickers_sticker_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSticker = null }) {
                    Text(stringResource(R.string.ai_stickers_sticker_delete_cancel))
                }
            },
        )
    }

    if (pendingDeletePack) {
        AlertDialog(
            onDismissRequest = { pendingDeletePack = false },
            title = { Text(stringResource(R.string.ai_stickers_pack_delete_title)) },
            text = { Text(stringResource(R.string.ai_stickers_pack_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeletePack = false
                    onDeletePack()
                }) {
                    Text(stringResource(R.string.ai_stickers_sticker_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletePack = false }) {
                    Text(stringResource(R.string.ai_stickers_sticker_delete_cancel))
                }
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StickerCell(
    sticker: Sticker,
    file: File,
    onLongPress: () -> Unit,
) {
    var bitmap by remember(sticker.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(sticker.id, file.absolutePath) {
        bitmap = withContext(Dispatchers.IO) {
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClick = { /* no commit from settings; keyboard owns insertion */ },
                onLongClick = onLongPress,
            ),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = stringResource(R.string.ai_stickers_picker_item_desc),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
