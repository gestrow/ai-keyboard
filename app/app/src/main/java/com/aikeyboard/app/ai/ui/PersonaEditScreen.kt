// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aikeyboard.app.ai.persona.FewShot
import com.aikeyboard.app.ai.persona.Persona
import com.aikeyboard.app.ai.storage.SecureStorage
import com.aikeyboard.app.latin.R
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaEditScreen(
    personaId: String?,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val storage = remember { SecureStorage.getInstance(context) }
    val existing = remember(personaId) {
        personaId?.let { id -> storage.getPersonas().firstOrNull { it.id == id } }
    }

    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var systemPrompt by remember { mutableStateOf(existing?.systemPrompt.orEmpty()) }
    val fewShots = remember {
        mutableStateListOf<FewShot>().apply { existing?.fewShots?.let { addAll(it) } }
    }
    var nameError by remember { mutableStateOf(false) }

    val titleRes = if (existing == null)
        R.string.ai_settings_persona_new
    else
        R.string.ai_settings_persona_edit

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleRes)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.ai_settings_persona_cancel),
                        )
                    }
                }
            )
        }
    ) { padding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = false
                },
                label = { Text(stringResource(R.string.ai_settings_persona_name)) },
                isError = nameError,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (nameError) {
                Text(
                    stringResource(R.string.ai_settings_persona_empty_name),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text(stringResource(R.string.ai_settings_persona_system_prompt)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Text(stringResource(R.string.ai_settings_persona_few_shots))

            fewShots.forEachIndexed { index, shot ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = shot.userInput,
                        onValueChange = { fewShots[index] = shot.copy(userInput = it) },
                        label = { Text(stringResource(R.string.ai_settings_persona_few_shot_user)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = shot.assistantResponse,
                        onValueChange = { fewShots[index] = shot.copy(assistantResponse = it) },
                        label = { Text(stringResource(R.string.ai_settings_persona_few_shot_assistant)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { fewShots.removeAt(index) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_ai_delete),
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text(stringResource(R.string.ai_settings_persona_remove_few_shot))
                    }
                    HorizontalDivider()
                }
            }

            TextButton(onClick = { fewShots.add(FewShot("", "")) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.padding(4.dp))
                Text(stringResource(R.string.ai_settings_persona_add_few_shot))
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    if (name.isBlank()) {
                        nameError = true
                        return@Button
                    }
                    val cleanedShots = fewShots.filter {
                        it.userInput.isNotBlank() || it.assistantResponse.isNotBlank()
                    }
                    val updated = if (existing != null) {
                        existing.copy(
                            name = name.trim(),
                            systemPrompt = systemPrompt,
                            fewShots = cleanedShots,
                        )
                    } else {
                        Persona(
                            id = UUID.randomUUID().toString(),
                            name = name.trim(),
                            systemPrompt = systemPrompt,
                            fewShots = cleanedShots,
                            isBuiltIn = false,
                        )
                    }
                    storage.savePersona(updated)
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.ai_settings_persona_save))
            }
        }
    }
}
