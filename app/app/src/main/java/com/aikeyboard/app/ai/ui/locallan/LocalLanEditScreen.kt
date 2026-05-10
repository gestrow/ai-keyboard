// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui.locallan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aikeyboard.app.ai.client.locallan.LocalLanApiFormat
import com.aikeyboard.app.ai.client.locallan.util.PublicIpValidator
import com.aikeyboard.app.latin.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalLanEditScreen(
    initialBaseUrl: String,
    initialApiFormat: LocalLanApiFormat,
    initialApiKey: String,
    initialModelName: String,
    onBack: () -> Unit,
    onSave: (baseUrl: String, format: LocalLanApiFormat, apiKey: String, modelName: String) -> Unit,
) {
    var baseUrl by remember { mutableStateOf(initialBaseUrl) }
    var format by remember { mutableStateOf(initialApiFormat) }
    var apiKey by remember { mutableStateOf(initialApiKey) }
    var modelName by remember { mutableStateOf(initialModelName) }
    var revealed by remember { mutableStateOf(false) }
    var pendingPublicIpConfirm by remember { mutableStateOf(false) }

    val saveEnabled = baseUrl.trim().isNotEmpty() && modelName.trim().isNotEmpty()

    fun commit() {
        onSave(baseUrl.trim(), format, apiKey.trim(), modelName.trim())
        onBack()
    }

    fun trySave() {
        val classification = PublicIpValidator.classifyUrl(baseUrl.trim())
        if (PublicIpValidator.isSafeForCleartext(classification)) {
            commit()
        } else {
            pendingPublicIpConfirm = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_settings_locallan_edit_title)) },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(stringResource(R.string.ai_settings_locallan_base_url_label)) },
                placeholder = { Text(stringResource(R.string.ai_settings_locallan_base_url_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(R.string.ai_settings_locallan_format_label),
                style = MaterialTheme.typography.labelLarge,
            )
            ApiFormatRadioRow(
                label = stringResource(R.string.ai_settings_locallan_format_ollama),
                selected = format == LocalLanApiFormat.OLLAMA,
                onSelect = { format = LocalLanApiFormat.OLLAMA },
            )
            ApiFormatRadioRow(
                label = stringResource(R.string.ai_settings_locallan_format_openai),
                selected = format == LocalLanApiFormat.OPENAI_COMPATIBLE,
                onSelect = { format = LocalLanApiFormat.OPENAI_COMPATIBLE },
            )

            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                label = { Text(stringResource(R.string.ai_settings_locallan_model_label)) },
                placeholder = { Text(stringResource(R.string.ai_settings_locallan_model_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(stringResource(R.string.ai_settings_locallan_apikey_label)) },
                singleLine = true,
                visualTransformation =
                    if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                ),
                trailingIcon = {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            painter = painterResource(
                                if (revealed) R.drawable.ic_visibility_off
                                else R.drawable.ic_visibility
                            ),
                            contentDescription = stringResource(
                                if (revealed) R.string.ai_settings_backend_hide_key
                                else R.string.ai_settings_backend_show_key
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(R.string.ai_settings_locallan_apikey_help),
                style = MaterialTheme.typography.bodySmall,
            )

            Button(
                onClick = ::trySave,
                enabled = saveEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.ai_settings_locallan_save))
            }
        }
    }

    if (pendingPublicIpConfirm) {
        AlertDialog(
            onDismissRequest = { pendingPublicIpConfirm = false },
            title = { Text(stringResource(R.string.ai_settings_locallan_public_ip_title)) },
            text = { Text(stringResource(R.string.ai_settings_locallan_public_ip_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingPublicIpConfirm = false
                    commit()
                }) {
                    Text(stringResource(R.string.ai_settings_locallan_save_anyway))
                }
            },
            dismissButton = {
                // Reuse the existing Cancel string — adding a Phase-10-specific cancel
                // string would be flagged as UnusedResources (lesson from Phase 9b's
                // four dropped strings).
                TextButton(onClick = { pendingPublicIpConfirm = false }) {
                    Text(stringResource(R.string.ai_settings_persona_cancel))
                }
            },
        )
    }
}

@Composable
private fun ApiFormatRadioRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
