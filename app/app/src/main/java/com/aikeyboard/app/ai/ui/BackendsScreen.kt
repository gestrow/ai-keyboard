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
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aikeyboard.app.ai.client.Provider
import com.aikeyboard.app.ai.storage.SecureStorage
import com.aikeyboard.app.latin.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackendsScreen(
    onBack: () -> Unit,
    onEditProvider: (Provider) -> Unit,
) {
    val context = LocalContext.current
    val storage = remember { SecureStorage.getInstance(context) }
    var configured by remember { mutableStateOf(storage.getConfiguredProviders()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                configured = storage.getConfiguredProviders()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_settings_hub_backends_title)) },
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
            Provider.entries.forEach { provider ->
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEditProvider(provider) },
                    headlineContent = { Text(provider.displayName) },
                    supportingContent = {
                        val statusRes = if (provider in configured)
                            R.string.ai_settings_backend_status_configured
                        else
                            R.string.ai_settings_backend_status_not_configured
                        Text(stringResource(statusRes))
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
