// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client

enum class Provider(
    val displayName: String,
    val storageKey: String,
    val defaultModel: String,
    val apiKeyHelpUrl: String,
) {
    ANTHROPIC(
        displayName = "Anthropic Claude",
        storageKey = "anthropic",
        defaultModel = "claude-sonnet-4-6",
        apiKeyHelpUrl = "https://console.anthropic.com/settings/keys",
    ),
    GOOGLE_GEMINI(
        displayName = "Google Gemini",
        storageKey = "google_gemini",
        defaultModel = "gemini-2.5-flash",
        apiKeyHelpUrl = "https://aistudio.google.com/apikey",
    );

    companion object {
        fun fromStorageKey(key: String): Provider? =
            entries.firstOrNull { it.storageKey == key }
    }
}
