// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import com.aikeyboard.app.latin.R
import com.aikeyboard.app.latin.settings.Settings
import com.aikeyboard.app.latin.utils.ToolbarMode
import com.aikeyboard.app.latin.utils.prefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardChromeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.prefs() }
    var showToolbar by remember {
        mutableStateOf(Settings.readToolbarMode(prefs) == ToolbarMode.EXPANDABLE)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_settings_keyboard_chrome_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.ai_settings_back),
                        )
                    }
                },
            )
        }
    ) { padding: PaddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.ai_settings_show_heliboard_toolbar_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.ai_settings_show_heliboard_toolbar_desc))
                },
                trailingContent = {
                    Switch(
                        checked = showToolbar,
                        onCheckedChange = { checked ->
                            showToolbar = checked
                            prefs.edit {
                                putString(
                                    Settings.PREF_TOOLBAR_MODE,
                                    if (checked) ToolbarMode.EXPANDABLE.name
                                    else ToolbarMode.SUGGESTION_STRIP.name,
                                )
                            }
                        },
                    )
                },
            )
        }
    }
}

