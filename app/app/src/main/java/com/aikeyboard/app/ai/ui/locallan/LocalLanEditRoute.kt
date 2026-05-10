// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui.locallan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.aikeyboard.app.ai.storage.SecureStorage

@Composable
fun LocalLanEditRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val storage = remember { SecureStorage.getInstance(context) }
    LocalLanEditScreen(
        initialBaseUrl = storage.getLocalLanBaseUrl(),
        initialApiFormat = storage.getLocalLanApiFormat(),
        initialApiKey = storage.getLocalLanApiKey(),
        initialModelName = storage.getLocalLanModelName(),
        onBack = onBack,
        onSave = { baseUrl, format, apiKey, model ->
            storage.setLocalLanBaseUrl(baseUrl)
            storage.setLocalLanApiFormat(format)
            storage.setLocalLanApiKey(apiKey)
            storage.setLocalLanModelName(model)
        },
    )
}
