// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aikeyboard.app.ai.client.Provider
import com.aikeyboard.app.ai.storage.SecureStorage
import com.aikeyboard.app.latin.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackendEditScreen(
    providerStorageKey: String?,
    onDone: () -> Unit,
) {
    val provider = providerStorageKey?.let { Provider.fromStorageKey(it) }
    if (provider == null) {
        // Defensive: nav arg missing or unknown provider — pop immediately, don't render.
        onDone()
        return
    }

    val context = LocalContext.current
    val storage = remember { SecureStorage.getInstance(context) }
    val initialKey = remember(provider) { storage.getApiKey(provider).orEmpty() }
    var apiKey by remember(provider) { mutableStateOf(initialKey) }
    var revealed by remember { mutableStateOf(false) }
    val isConfigured = initialKey.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(provider.displayName) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(stringResource(R.string.ai_settings_backend_api_key_label)) },
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
                text = stringResource(R.string.ai_settings_backend_help_prefix),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, provider.apiKeyHelpUrl.toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                }
            ) {
                Text(provider.apiKeyHelpUrl)
            }

            val saveEnabled = apiKey.isNotEmpty() && apiKey != initialKey
            Button(
                onClick = {
                    storage.saveApiKey(provider, apiKey)
                    onDone()
                },
                enabled = saveEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.ai_settings_backend_save))
            }

            if (isConfigured) {
                OutlinedButton(
                    onClick = {
                        storage.deleteApiKey(provider)
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.ai_settings_backend_delete))
                }
            }
        }
    }
}
