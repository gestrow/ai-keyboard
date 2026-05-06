// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui.stickers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aikeyboard.app.ai.sticker.StickerPack
import com.aikeyboard.app.latin.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerPacksScreen(
    packs: List<StickerPack>,
    onBack: () -> Unit,
    onOpenPack: (String) -> Unit,
    onImport: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_settings_hub_stickers_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                            contentDescription = stringResource(R.string.ai_stickers_packs_import_action),
                        )
                    }
                },
            )
        }
    ) { padding: PaddingValues ->
        if (packs.isEmpty() || packs.all { it.stickers.isEmpty() }) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(stringResource(R.string.ai_stickers_packs_empty_title))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.ai_stickers_packs_empty_body),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onImport) {
                        Text(stringResource(R.string.ai_stickers_packs_import_action))
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Top,
            ) {
                items(packs, key = { it.id }) { pack ->
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenPack(pack.id) },
                        headlineContent = { Text(pack.name) },
                        supportingContent = {
                            Text(pluralStickerCount(pack.stickers.size))
                        },
                        trailingContent = {
                            Icon(
                                painter = painterResource(R.drawable.ic_chevron_right),
                                contentDescription = null,
                            )
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun pluralStickerCount(count: Int): String =
    if (count == 1) stringResource(R.string.ai_stickers_pack_count_one)
    else stringResource(R.string.ai_stickers_pack_count_many, count)
