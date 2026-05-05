// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.aikeyboard.app.latin.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubScreen(
    onOpenPersonas: () -> Unit,
    onOpenKeyboardChrome: () -> Unit,
    onOpenBackends: () -> Unit,
    onOpenAlwaysOn: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.ai_settings_title)) })
        }
    ) { padding: PaddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            HubRow(
                titleRes = R.string.ai_settings_hub_personas_title,
                descriptionRes = R.string.ai_settings_hub_personas_desc,
                iconRes = R.drawable.ic_settings_default,
                onClick = onOpenPersonas,
            )
            HorizontalDivider()
            HubRow(
                titleRes = R.string.ai_settings_hub_keyboard_title,
                descriptionRes = R.string.ai_settings_hub_keyboard_desc,
                iconRes = R.drawable.ic_settings_advanced,
                onClick = onOpenKeyboardChrome,
            )
            HorizontalDivider()
            HubRow(
                titleRes = R.string.ai_settings_hub_backends_title,
                descriptionRes = R.string.ai_settings_hub_backends_desc,
                iconRes = R.drawable.ic_settings_about_github,
                onClick = onOpenBackends,
            )
            HorizontalDivider()
            HubRow(
                titleRes = R.string.ai_settings_hub_always_on_title,
                descriptionRes = R.string.ai_settings_hub_always_on_desc,
                iconRes = R.drawable.ic_read_respond,
                onClick = onOpenAlwaysOn,
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun HubRow(
    titleRes: Int,
    descriptionRes: Int,
    iconRes: Int,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        leadingContent = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
            )
        },
        headlineContent = { Text(stringResource(titleRes)) },
        supportingContent = { Text(stringResource(descriptionRes)) },
        trailingContent = {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
            )
        },
    )
}
