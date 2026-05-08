// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui.stickers

import android.graphics.BitmapFactory
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.aikeyboard.app.ai.sticker.AddToWhatsAppHelper
import com.aikeyboard.app.ai.sticker.Sticker
import com.aikeyboard.app.ai.sticker.StickerPack
import com.aikeyboard.app.ai.sticker.StickerPackValidator
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
    whatsAppStatus: AddToWhatsAppHelper.WhatsAppStatus,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onDeletePack: () -> Unit,
    onDeleteSticker: (String) -> Unit,
    onImport: () -> Unit,
    onChooseTrayIcon: () -> Unit,
    onSetPublisher: (String) -> Unit,
    onSetStickerEmojis: (stickerId: String, emojis: List<String>) -> Unit,
    onAddToWhatsApp: () -> Unit,
) {
    var name by remember(pack.id) { mutableStateOf(pack.name) }
    var publisher by remember(pack.id) { mutableStateOf(pack.publisher) }
    var pendingDeleteSticker by remember { mutableStateOf<String?>(null) }
    var pendingDeletePack by remember { mutableStateOf(false) }

    // Validator runs on every recomposition; it's a pure list build over <=30 stickers.
    val issues = remember(pack) { StickerPackValidator.validate(pack) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_stickers_pack_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        // Save name + publisher on back-out if either changed and the field is non-empty.
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty() && trimmed != pack.name) {
                            onRename(trimmed)
                        }
                        val trimmedPublisher = publisher.trim()
                        if (trimmedPublisher != pack.publisher) {
                            onSetPublisher(trimmedPublisher)
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
            TrayIconRow(
                pack = pack,
                packDir = packDir,
                onChoose = onChooseTrayIcon,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.ai_stickers_pack_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Deferred-save: writing on every keystroke would bump imageDataVersion
            // per character and re-emit the StateFlow to every observing screen.
            // Save on blur (focus loss) only.
            OutlinedTextField(
                value = publisher,
                onValueChange = { publisher = it },
                label = { Text(stringResource(R.string.ai_stickers_pack_publisher_label)) },
                supportingText = { Text(stringResource(R.string.ai_stickers_pack_publisher_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state ->
                        if (!state.isFocused) {
                            val trimmed = publisher.trim()
                            if (trimmed != pack.publisher) onSetPublisher(trimmed)
                        }
                    },
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
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pack.stickers, key = { it.id }) { sticker ->
                        StickerCell(
                            sticker = sticker,
                            file = File(packDir, sticker.fileName),
                            onLongPress = { pendingDeleteSticker = sticker.id },
                            onSetEmojis = { emojis -> onSetStickerEmojis(sticker.id, emojis) },
                        )
                    }
                }
            }

            HorizontalDivider()

            // "Add to WhatsApp" + preflight chips. Issues render simultaneously, one
            // chip per failing rule, so the user sees every problem at once instead
            // of error-chasing.
            if (issues.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    for (issue in issues) {
                        AssistChip(
                            onClick = { /* informational */ },
                            label = { Text(stringResource(issueResId(issue))) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ai_lock),
                                    contentDescription = null,
                                )
                            },
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }

            Button(
                onClick = onAddToWhatsApp,
                enabled = issues.isEmpty()
                    && whatsAppStatus != AddToWhatsAppHelper.WhatsAppStatus.NONE,
            ) {
                Text(stringResource(R.string.ai_stickers_whatsapp_add))
            }

            if (whatsAppStatus == AddToWhatsAppHelper.WhatsAppStatus.NONE) {
                Text(
                    stringResource(R.string.ai_stickers_whatsapp_not_installed),
                    style = MaterialTheme.typography.bodySmall,
                )
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

@Composable
private fun TrayIconRow(
    pack: StickerPack,
    packDir: File,
    onChoose: () -> Unit,
) {
    val tray = pack.trayIconFile?.let { File(packDir, it) }
    var bitmap by remember(pack.id, pack.imageDataVersion, pack.trayIconFile) {
        mutableStateOf<Bitmap?>(null)
    }
    LaunchedEffect(pack.id, pack.imageDataVersion, pack.trayIconFile) {
        bitmap = withContext(Dispatchers.IO) {
            if (tray != null && tray.exists()) BitmapFactory.decodeFile(tray.absolutePath) else null
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(64.dp),
            contentAlignment = Alignment.Center,
        ) {
            val current = bitmap
            if (current != null) {
                Image(
                    bitmap = current.asImageBitmap(),
                    contentDescription = stringResource(R.string.ai_stickers_tray_section),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_sticker),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.ai_stickers_tray_section), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.ai_stickers_tray_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(onClick = onChoose) {
            Text(
                stringResource(
                    if (pack.trayIconFile.isNullOrEmpty())
                        R.string.ai_stickers_tray_choose
                    else
                        R.string.ai_stickers_tray_replace
                )
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StickerCell(
    sticker: Sticker,
    file: File,
    onLongPress: () -> Unit,
    onSetEmojis: (List<String>) -> Unit,
) {
    var bitmap by remember(sticker.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(sticker.id, file.absolutePath) {
        bitmap = withContext(Dispatchers.IO) {
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        }
    }
    var emojis by remember(sticker.id) {
        mutableStateOf(sticker.emojis.joinToString(" "))
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
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
        OutlinedTextField(
            value = emojis,
            onValueChange = { emojis = it },
            placeholder = { Text(stringResource(R.string.ai_stickers_emoji_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    if (!state.isFocused) {
                        val parsed = parseEmojis(emojis)
                        if (parsed != sticker.emojis) onSetEmojis(parsed)
                    }
                },
            textStyle = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun issueResId(issue: StickerPackValidator.Issue): Int = when (issue) {
    StickerPackValidator.Issue.TOO_FEW_STICKERS -> R.string.ai_stickers_validation_too_few
    StickerPackValidator.Issue.TOO_MANY_STICKERS -> R.string.ai_stickers_validation_too_many
    StickerPackValidator.Issue.MISSING_TRAY_ICON -> R.string.ai_stickers_validation_no_tray
    StickerPackValidator.Issue.MISSING_PUBLISHER -> R.string.ai_stickers_validation_no_publisher
    StickerPackValidator.Issue.NAME_TOO_LONG -> R.string.ai_stickers_validation_name_long
    StickerPackValidator.Issue.PUBLISHER_TOO_LONG -> R.string.ai_stickers_validation_publisher_long
    StickerPackValidator.Issue.IDENTIFIER_INVALID -> R.string.ai_stickers_validation_id_invalid
}

/**
 * "first 3 codepoint clusters" parser: trim, split on whitespace, take first 3
 * tokens. Approximate — a full grapheme-cluster parser using BreakIterator would
 * handle ZWJ-joined emoji sequences and skin-tone modifiers; the user-facing
 * contract is "separate emojis with spaces", which this matches. Phase 12 polish
 * can swap in a proper parser if real-world usage shows mismatches.
 */
internal fun parseEmojis(input: String): List<String> {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return emptyList()
    return trimmed.split(Regex("\\s+")).take(3)
}
