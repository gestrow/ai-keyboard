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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aikeyboard.app.ai.client.BackendStrategy
import com.aikeyboard.app.ai.client.Provider
import com.aikeyboard.app.ai.storage.SecureStorage
import com.aikeyboard.app.ai.termux.TermuxOrchestrator
import com.aikeyboard.app.latin.R
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackendsScreen(
    onBack: () -> Unit,
    onEditProvider: (Provider) -> Unit,
    onOpenTermuxBridge: () -> Unit,
    onOpenLocalLanEdit: () -> Unit,
) {
    val context = LocalContext.current
    val storage = remember { SecureStorage.getInstance(context) }
    val orchestrator = remember { TermuxOrchestrator.getInstance(context) }
    var configured by remember { mutableStateOf(storage.getConfiguredProviders()) }
    var termuxStatus by remember { mutableStateOf<TermuxOrchestrator.Status?>(null) }
    var termuxAuthCount by remember { mutableStateOf<Int?>(null) }
    var activeStrategy by remember { mutableStateOf(storage.getSelectedBackendStrategy()) }
    var activeRemoteProvider by remember { mutableStateOf(storage.getSelectedProvider()) }
    var localLanBaseUrl by remember { mutableStateOf(storage.getLocalLanBaseUrl()) }
    var localLanModelName by remember { mutableStateOf(storage.getLocalLanModelName()) }
    // Bumped each ON_RESUME so the async Termux probe and stored-selection rederivation
    // re-run after the user comes back from the status screen (where bridge state +
    // selectedTermuxProvider can change). Without this, the screen would render stale
    // BRIDGE_RUNNING / radio state until next composition.
    var resumeCount by remember { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                configured = storage.getConfiguredProviders()
                activeStrategy = storage.getSelectedBackendStrategy()
                activeRemoteProvider = storage.getSelectedProvider()
                localLanBaseUrl = storage.getLocalLanBaseUrl()
                localLanModelName = storage.getLocalLanModelName()
                resumeCount += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    LaunchedEffect(resumeCount) {
        termuxStatus = orchestrator.detectStatus()
        termuxAuthCount = if (termuxStatus == TermuxOrchestrator.Status.BRIDGE_RUNNING) {
            orchestrator.fetchProviders()?.count { it.available } ?: 0
        } else {
            null
        }
    }

    val termuxSelectable = termuxStatus == TermuxOrchestrator.Status.BRIDGE_RUNNING
        && (termuxAuthCount ?: 0) > 0
    val localLanSelectable = localLanBaseUrl.isNotEmpty() && localLanModelName.isNotEmpty()

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
            Text(
                text = stringResource(R.string.ai_settings_backends_active_header),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            )

            Provider.entries.forEach { provider ->
                val providerConfigured = provider in configured
                val isActive = activeStrategy == BackendStrategy.REMOTE_API
                    && activeRemoteProvider == provider
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEditProvider(provider) },
                    leadingContent = {
                        RadioButton(
                            selected = isActive,
                            enabled = providerConfigured,
                            onClick = if (providerConfigured) {
                                {
                                    storage.setSelectedBackendStrategy(BackendStrategy.REMOTE_API)
                                    storage.setSelectedProvider(provider)
                                    activeStrategy = BackendStrategy.REMOTE_API
                                    activeRemoteProvider = provider
                                }
                            } else null,
                        )
                    },
                    headlineContent = { Text(provider.displayName) },
                    supportingContent = {
                        val statusRes = if (providerConfigured)
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

            val localLanActive = activeStrategy == BackendStrategy.LOCAL_LAN
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenLocalLanEdit() },
                leadingContent = {
                    RadioButton(
                        selected = localLanActive,
                        enabled = localLanSelectable,
                        onClick = if (localLanSelectable) {
                            {
                                storage.setSelectedBackendStrategy(BackendStrategy.LOCAL_LAN)
                                activeStrategy = BackendStrategy.LOCAL_LAN
                            }
                        } else null,
                    )
                },
                headlineContent = {
                    Text(stringResource(R.string.ai_settings_backend_locallan_title))
                },
                supportingContent = {
                    Text(localLanStatusText(localLanBaseUrl, localLanModelName))
                },
                trailingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                    )
                },
            )
            HorizontalDivider()

            val termuxActive = activeStrategy == BackendStrategy.TERMUX_BRIDGE
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenTermuxBridge() },
                leadingContent = {
                    RadioButton(
                        selected = termuxActive,
                        enabled = termuxSelectable,
                        onClick = if (termuxSelectable) {
                            {
                                storage.setSelectedBackendStrategy(BackendStrategy.TERMUX_BRIDGE)
                                activeStrategy = BackendStrategy.TERMUX_BRIDGE
                                // Note: doesn't set selectedTermuxProvider — user picks that on
                                // the status screen, which auto-picks the first available one
                                // if the field is null when they land.
                            }
                        } else null,
                    )
                },
                headlineContent = {
                    Text(stringResource(R.string.ai_settings_backend_termux_title))
                },
                supportingContent = {
                    val baseText = termuxStatusText(termuxStatus, termuxAuthCount)
                    if (!termuxSelectable && termuxStatus != null) {
                        // Hint that a configuration step is required, alongside the existing
                        // status line — keeps the row's primary supporting text intact while
                        // making the disabled-radio reason explicit.
                        Text("$baseText\n${stringResource(R.string.ai_settings_backends_radio_disabled)}")
                    } else {
                        Text(baseText)
                    }
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

@Composable
private fun localLanStatusText(baseUrl: String, modelName: String): String {
    if (baseUrl.isEmpty()) {
        return stringResource(R.string.ai_settings_backend_locallan_desc_unconfigured)
    }
    val host = runCatching { URI(baseUrl).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: baseUrl
    return if (modelName.isEmpty()) {
        stringResource(R.string.ai_settings_backend_locallan_status_model_required, host)
    } else {
        stringResource(R.string.ai_settings_backend_locallan_status_configured, host, modelName)
    }
}

@Composable
private fun termuxStatusText(
    status: TermuxOrchestrator.Status?,
    authCount: Int?,
): String = when (status) {
    null -> stringResource(R.string.ai_settings_backend_termux_status_unknown)
    TermuxOrchestrator.Status.TERMUX_NOT_INSTALLED ->
        stringResource(R.string.ai_settings_backend_termux_status_not_installed)
    TermuxOrchestrator.Status.BRIDGE_RUNNING -> {
        val n = authCount ?: 0
        if (n == 0) stringResource(R.string.ai_settings_backend_termux_status_running_zero)
        else stringResource(R.string.ai_settings_backend_termux_status_running, n)
    }
    else -> stringResource(R.string.ai_settings_backend_termux_status_not_running)
}
