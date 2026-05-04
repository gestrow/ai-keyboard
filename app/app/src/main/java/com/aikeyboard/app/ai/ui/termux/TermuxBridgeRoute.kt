// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui.termux

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aikeyboard.app.ai.storage.SecureStorage
import com.aikeyboard.app.ai.termux.TermuxOrchestrator
import com.aikeyboard.app.latin.R

/**
 * Single entry point for the Termux Bridge sub-flow under
 * AiSettings → Backends → Termux Bridge. Probes the orchestrator's
 * detectStatus() and dispatches to the wizard (any state up to BRIDGE_NOT_RUNNING)
 * or the status screen (BRIDGE_RUNNING).
 *
 * The status is held as a mutable state so child screens can request a
 * recheck after they complete a step (e.g. after the user taps "I've
 * completed setup, check now").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermuxBridgeRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val orchestrator = remember { TermuxOrchestrator.getInstance(context) }
    val storage = remember { SecureStorage.getInstance(context) }
    var status by remember { mutableStateOf<TermuxOrchestrator.Status?>(null) }
    var rechecks by remember { mutableIntStateOf(0) }

    LaunchedEffect(rechecks) {
        status = orchestrator.detectStatus()
    }

    when (val s = status) {
        null -> {
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
            ) { padding ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
        TermuxOrchestrator.Status.BRIDGE_RUNNING -> {
            TermuxBridgeStatusScreen(
                orchestrator = orchestrator,
                storage = storage,
                onBack = onBack,
                onRecheck = { rechecks += 1 },
            )
        }
        else -> {
            TermuxSetupWizardScreen(
                orchestrator = orchestrator,
                status = s,
                onBack = onBack,
                onRecheck = { rechecks += 1 },
            )
        }
    }
}
