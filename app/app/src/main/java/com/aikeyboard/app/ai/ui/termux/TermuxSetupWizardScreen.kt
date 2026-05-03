// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui.termux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aikeyboard.app.ai.termux.TermuxOrchestrator
import com.aikeyboard.app.latin.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermuxSetupWizardScreen(
    orchestrator: TermuxOrchestrator,
    status: TermuxOrchestrator.Status,
    onBack: () -> Unit,
    onRecheck: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.termux_wizard_title)) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (status) {
                TermuxOrchestrator.Status.TERMUX_NOT_INSTALLED ->
                    StepInstallTermux(orchestrator, onRecheck, onBack)
                TermuxOrchestrator.Status.TERMUX_INSTALLED_NOT_CONFIGURED,
                TermuxOrchestrator.Status.SCRIPT_NOT_DEPLOYED,
                TermuxOrchestrator.Status.BRIDGE_NOT_RUNNING ->
                    StepStartBridge(orchestrator, onRecheck)
                TermuxOrchestrator.Status.BRIDGE_RUNNING -> {
                    // The route normally hands BRIDGE_RUNNING to the status
                    // screen, but if we're rendered with this status (race
                    // between setup completion and detect), we still need a
                    // visible state — fall through to step 4.
                    StepBridgeReady(orchestrator, onRecheck)
                }
            }
        }
    }
}

@Composable
private fun StepInstallTermux(
    orchestrator: TermuxOrchestrator,
    onRecheck: () -> Unit,
    onBack: () -> Unit,
) {
    Text(
        text = stringResource(R.string.termux_wizard_step_install_title),
        style = MaterialTheme.typography.titleLarge,
    )
    Text(stringResource(R.string.termux_wizard_step_install_body))
    Button(
        onClick = { orchestrator.launchFDroidTermuxPage() },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.termux_wizard_install_fdroid)) }
    OutlinedButton(
        onClick = onRecheck,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.termux_wizard_already_installed)) }
    OutlinedButton(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.termux_wizard_cancel)) }
}

private const val BOOTSTRAP_COMMAND =
    "curl -fsSL https://raw.githubusercontent.com/aikeyboard/ai-keyboard/main/setup/setup.sh > " +
        "\$HOME/ai-keyboard-setup.sh && bash \$HOME/ai-keyboard-setup.sh"

@Composable
private fun StepStartBridge(
    orchestrator: TermuxOrchestrator,
    onRecheck: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var lastExitCode by remember { mutableStateOf<Int?>(null) }

    // Background polling: when the bridge becomes reachable, request the route
    // to recheck so it can swap to the status screen. Cancellable on screen exit.
    val healthFlow = remember { orchestrator.observeBridgeHealth(maxSeconds = 600) }
    val healthState by healthFlow.collectAsState(initial = TermuxOrchestrator.HealthState.Polling)
    LaunchedEffect(healthState) {
        if (healthState is TermuxOrchestrator.HealthState.Healthy) {
            onRecheck()
        }
    }

    Text(
        text = stringResource(R.string.termux_wizard_step_deploy_title),
        style = MaterialTheme.typography.titleLarge,
    )
    Text(stringResource(R.string.termux_wizard_step_deploy_body))
    CodeBlock(text = BOOTSTRAP_COMMAND)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                copyToClipboard(context, "ai-keyboard-bootstrap", BOOTSTRAP_COMMAND)
                Toast.makeText(context, R.string.termux_wizard_copied, Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.termux_wizard_copy_command)) }
        OutlinedButton(
            onClick = { orchestrator.launchTermuxApp() },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.termux_wizard_open_termux)) }
    }
    Text(
        text = stringResource(R.string.termux_wizard_step_deploy_dev_note),
        style = MaterialTheme.typography.bodySmall,
    )

    Text(
        text = stringResource(R.string.termux_wizard_step_start_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(stringResource(R.string.termux_wizard_step_start_body))
    Button(
        onClick = {
            running = true
            lastError = null
            lastExitCode = null
            scope.launch {
                val result = orchestrator.runSetupScript(emptyList(), foreground = true)
                running = false
                if (result.exitCode == 0) {
                    onRecheck()
                } else {
                    lastExitCode = result.exitCode
                    lastError = result.stderr.takeIf { it.isNotBlank() } ?: result.stdout
                }
            }
        },
        enabled = !running,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (running) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 8.dp),
                strokeWidth = 2.dp,
            )
            Text(stringResource(R.string.termux_wizard_running_setup))
        } else {
            Text(stringResource(R.string.termux_wizard_start_bridge))
        }
    }

    OutlinedButton(
        onClick = onRecheck,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.termux_wizard_completed_setup)) }

    if (healthState is TermuxOrchestrator.HealthState.Unreachable
        || healthState is TermuxOrchestrator.HealthState.Polling) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 12.dp),
                strokeWidth = 2.dp,
            )
            Text(stringResource(R.string.termux_wizard_polling))
        }
    }

    val exitCode = lastExitCode
    val errMessage = lastError
    if (exitCode != null) {
        Text(
            text = stringResource(R.string.termux_wizard_setup_failed, exitCode),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleSmall,
        )
        if (!errMessage.isNullOrBlank()) {
            // Truncate to ~2KB so we never blow up the UI; the full payload is
            // still copyable. We never log this — CLI prompt text can leak in.
            CodeBlock(
                text = errMessage.take(2048),
                maxLines = 8,
            )
            OutlinedButton(
                onClick = {
                    copyToClipboard(context, "ai-keyboard-setup-log", errMessage)
                    Toast.makeText(context, R.string.termux_wizard_log_copied, Toast.LENGTH_SHORT).show()
                },
            ) { Text(stringResource(R.string.termux_wizard_copy_log)) }
        }
    }
}

@Composable
private fun StepBridgeReady(
    orchestrator: TermuxOrchestrator,
    onRecheck: () -> Unit,
) {
    Text(
        text = stringResource(R.string.termux_wizard_step_done_title),
        style = MaterialTheme.typography.titleLarge,
    )
    Text(stringResource(R.string.termux_wizard_step_done_body))
    Button(
        onClick = onRecheck,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.termux_wizard_done)) }
}

@Composable
private fun CodeBlock(text: String, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(12.dp),
    )
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
