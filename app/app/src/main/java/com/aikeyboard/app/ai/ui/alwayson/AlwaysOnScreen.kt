// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui.alwayson

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlwaysOnScreen(
    enabled: Boolean,
    a11yEnabled: Boolean,
    notifGranted: Boolean,
    consented: Boolean,
    supported: Boolean,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onOpenA11ySettings: () -> Unit,
    onGrantNotif: () -> Unit,
    onReviewConsent: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_always_on_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.ai_settings_back),
                        )
                    }
                },
            )
        },
    ) { padding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.ai_always_on_toggle_label))
                },
                trailingContent = {
                    Switch(
                        checked = enabled,
                        onCheckedChange = onToggle,
                        enabled = supported,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            HorizontalDivider()

            // Status section: three rows, each with an action chip when the
            // corresponding gate is unsatisfied.
            Text(
                stringResource(R.string.ai_always_on_status_header),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            )

            StatusRow(
                statusText = stringResource(
                    if (a11yEnabled) R.string.ai_always_on_status_a11y_enabled
                    else R.string.ai_always_on_status_a11y_disabled
                ),
                actionLabel = if (a11yEnabled) null else
                    stringResource(R.string.ai_always_on_action_open_a11y),
                onAction = onOpenA11ySettings,
            )
            StatusRow(
                statusText = stringResource(
                    if (notifGranted) R.string.ai_always_on_status_notif_granted
                    else R.string.ai_always_on_status_notif_required
                ),
                actionLabel = if (notifGranted) null else
                    stringResource(R.string.ai_always_on_action_grant_notif),
                onAction = onGrantNotif,
            )
            StatusRow(
                statusText = stringResource(
                    if (consented) R.string.ai_always_on_status_consent_yes
                    else R.string.ai_always_on_status_consent_no
                ),
                actionLabel = if (consented) null else
                    stringResource(R.string.ai_always_on_action_review_consent),
                onAction = onReviewConsent,
            )
            HorizontalDivider()

            // Body text
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.ai_always_on_body_persistent_notice),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.ai_always_on_body_tile_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.ai_always_on_body_consent_reminder),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.ai_always_on_body_how_to_add_tile),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (!supported) {
                HorizontalDivider()
                Text(
                    stringResource(R.string.ai_always_on_not_supported_play),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusRow(
    statusText: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
