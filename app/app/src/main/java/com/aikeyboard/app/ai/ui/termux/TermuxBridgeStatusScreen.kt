// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui.termux

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aikeyboard.app.ai.termux.TermuxOrchestrator
import com.aikeyboard.app.latin.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermuxBridgeStatusScreen(
    orchestrator: TermuxOrchestrator,
    onBack: () -> Unit,
    onRecheck: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf<List<TermuxOrchestrator.ProviderStatus>?>(null) }
    var uptime by remember { mutableStateOf<Long?>(null) }
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }
    var lastError by remember { mutableStateOf<Pair<Int, String>?>(null) }
    // Bumped whenever an action completes so the status snapshot re-fetches
    // (uptime, provider auth) without having to navigate away and back.
    var refreshCounter by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshCounter) {
        orchestrator.observeBridgeHealth(maxSeconds = 1).collect { state ->
            if (state is TermuxOrchestrator.HealthState.Healthy) {
                providers = state.providers
                uptime = state.uptimeSeconds
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.termux_status_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.termux_status_running),
                style = MaterialTheme.typography.titleLarge,
            )
            uptime?.let {
                Text(
                    text = stringResource(R.string.termux_status_uptime, formatUptime(it)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            providers?.forEach { p ->
                ProviderRow(
                    provider = p,
                    busy = pendingAction is PendingAction.Reauth
                        && (pendingAction as PendingAction.Reauth).cliName == p.id,
                    onReauth = {
                        val provider = providerForCliName(p.id) ?: return@ProviderRow
                        pendingAction = PendingAction.Reauth(p.id)
                        lastError = null
                        scope.launch {
                            val result = orchestrator.reauthProvider(provider)
                            pendingAction = null
                            if (result.exitCode != 0) {
                                lastError = result.exitCode to (result.stderr.takeIf { it.isNotBlank() } ?: result.stdout)
                            }
                            // /providers re-check picks up the new auth state.
                            refreshCounter += 1
                        }
                    },
                )
                HorizontalDivider()
            }

            Button(
                onClick = {
                    pendingAction = PendingAction.Restart
                    lastError = null
                    scope.launch {
                        val result = orchestrator.runSetupScript(emptyList(), foreground = true)
                        pendingAction = null
                        if (result.exitCode != 0) {
                            lastError = result.exitCode to (result.stderr.takeIf { it.isNotBlank() } ?: result.stdout)
                        }
                        refreshCounter += 1
                        onRecheck()
                    }
                },
                enabled = pendingAction == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (pendingAction is PendingAction.Restart) {
                    InlineSpinner()
                    Text(stringResource(R.string.termux_status_restarting))
                } else {
                    Text(stringResource(R.string.termux_status_restart))
                }
            }

            OutlinedButton(
                onClick = {
                    pendingAction = PendingAction.Stop
                    lastError = null
                    scope.launch {
                        val result = orchestrator.stopBridge()
                        pendingAction = null
                        if (result.exitCode != 0) {
                            lastError = result.exitCode to (result.stderr.takeIf { it.isNotBlank() } ?: result.stdout)
                        }
                        onRecheck()
                    }
                },
                enabled = pendingAction == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (pendingAction is PendingAction.Stop) {
                    InlineSpinner()
                    Text(stringResource(R.string.termux_status_stopping))
                } else {
                    Text(stringResource(R.string.termux_status_stop))
                }
            }

            lastError?.let { (code, _) ->
                Text(
                    text = stringResource(R.string.termux_status_action_failed, code),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ProviderRow(
    provider: TermuxOrchestrator.ProviderStatus,
    busy: Boolean,
    onReauth: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName(provider.id),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    if (provider.available) R.string.termux_status_provider_authenticated
                    else R.string.termux_status_provider_unauthenticated
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (provider.available) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
        }
        OutlinedButton(onClick = onReauth, enabled = !busy) {
            if (busy) {
                InlineSpinner()
                Text(stringResource(R.string.termux_status_reauthenticating, displayName(provider.id)))
            } else {
                Text(stringResource(R.string.termux_status_provider_reauth))
            }
        }
    }
}

@Composable
private fun InlineSpinner() {
    CircularProgressIndicator(
        modifier = Modifier.padding(end = 8.dp),
        strokeWidth = 2.dp,
    )
}

private sealed interface PendingAction {
    data class Reauth(val cliName: String) : PendingAction
    data object Restart : PendingAction
    data object Stop : PendingAction
}

private fun providerForCliName(name: String): TermuxOrchestrator.Provider? =
    TermuxOrchestrator.Provider.entries.firstOrNull { it.cliName == name }

private fun displayName(cliName: String): String = when (cliName) {
    "claude" -> "Claude"
    "gemini" -> "Gemini"
    else -> cliName
}

private fun formatUptime(seconds: Long): String {
    val s = seconds % 60
    val m = (seconds / 60) % 60
    val h = seconds / 3_600
    return when {
        h > 0 -> "%dh %02dm".format(h, m)
        m > 0 -> "%dm %02ds".format(m, s)
        else -> "%ds".format(s)
    }
}
