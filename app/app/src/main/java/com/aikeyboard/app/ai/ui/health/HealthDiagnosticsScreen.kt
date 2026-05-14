// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui.health

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aikeyboard.app.latin.R

/**
 * Phase 12 §10: pure-Compose surface for the health diagnostics screen.
 * State is hoisted via `HealthDiagnosticsRoute`; this composable knows only
 * about rendering. Rows whose status is HIDDEN are filtered out so the
 * screen layout matches what `HealthReportFormat.format` would emit to the
 * clipboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HealthDiagnosticsScreen(
    isLoading: Boolean,
    diagnostics: List<HealthDiagnostic>,
    onBack: () -> Unit,
    onCopy: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_health_diagnostics_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.ai_settings_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onCopy, enabled = !isLoading) {
                        Text(stringResource(R.string.ai_health_diagnostics_copy))
                    }
                },
            )
        },
    ) { padding: PaddingValues ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            val visible = diagnostics.filter { it.status != HealthStatus.HIDDEN }
            visible.forEach { d ->
                ListItem(
                    modifier = Modifier.fillMaxWidth(),
                    headlineContent = { Text(d.label) },
                    supportingContent = d.detail?.let { detail -> { Text(detail) } },
                    trailingContent = {
                        Text(
                            text = d.status.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = when (d.status) {
                                HealthStatus.OK -> MaterialTheme.colorScheme.primary
                                HealthStatus.WARN -> MaterialTheme.colorScheme.tertiary
                                HealthStatus.FAIL -> MaterialTheme.colorScheme.error
                                HealthStatus.NEUTRAL -> MaterialTheme.colorScheme.outline
                                HealthStatus.HIDDEN -> MaterialTheme.colorScheme.outline
                            },
                        )
                    },
                )
                HorizontalDivider()
            }
            Text(
                text = stringResource(R.string.ai_health_diagnostics_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
