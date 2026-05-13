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
    ),
    // Phase 11: xAI Grok uses OpenAI-compatible /v1/chat/completions wire
    // format; RemoteApiBackend.streamGrok reuses Phase 10's
    // OpenAiCompatStreamParser for the SSE response.
    XAI_GROK(
        displayName = "xAI Grok",
        storageKey = "xai_grok",
        defaultModel = "grok-2-latest",
        apiKeyHelpUrl = "https://console.x.ai",
    );

    companion object {
        fun fromStorageKey(key: String): Provider? =
            entries.firstOrNull { it.storageKey == key }
    }
}
