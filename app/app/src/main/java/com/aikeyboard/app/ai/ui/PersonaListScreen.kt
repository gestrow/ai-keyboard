// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aikeyboard.app.ai.persona.Persona
import com.aikeyboard.app.ai.storage.SecureStorage
import com.aikeyboard.app.latin.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaListScreen(
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenKeyboardChrome: () -> Unit,
) {
    val context = LocalContext.current
    val storage = remember { SecureStorage.getInstance(context) }
    val personas = remember { mutableStateListOf<Persona>() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lockedMessage = stringResource(R.string.ai_settings_persona_builtin_locked)

    fun reload() {
        personas.clear()
        personas.addAll(storage.getPersonas())
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_settings_personas_title)) },
                actions = {
                    IconButton(onClick = onOpenKeyboardChrome) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings_advanced),
                            contentDescription = stringResource(R.string.ai_settings_keyboard_chrome_title),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = stringResource(R.string.ai_settings_persona_new),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding: PaddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            items(personas, key = { it.id }) { persona ->
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEdit(persona.id) },
                    headlineContent = { Text(persona.name) },
                    supportingContent = {
                        if (persona.systemPrompt.isNotBlank()) {
                            Text(persona.systemPrompt, maxLines = 2)
                        }
                    },
                    trailingContent = {
                        if (persona.isBuiltIn) {
                            IconButton(
                                onClick = { scope.launch { snackbar.showSnackbar(lockedMessage) } }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ai_lock),
                                    contentDescription = null,
                                )
                            }
                        } else {
                            IconButton(onClick = {
                                storage.deletePersona(persona.id)
                                reload()
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ai_delete),
                                    contentDescription = stringResource(R.string.ai_settings_persona_delete),
                                )
                            }
                        }
                    }
                )
                HorizontalDivider()
            }
        }
    }
}
