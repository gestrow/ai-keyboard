// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.locallan

import com.aikeyboard.app.ai.persona.FewShot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class ChatMessage(val role: String, val content: String)

@Serializable
internal data class OllamaChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
)

@Serializable
internal data class OpenAiChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
)

internal object LocalLanRequestBuilder {

    /**
     * Order matches Phase 3b's RemoteApiBackend exactly so Ollama and OpenAI
     * servers see the same message sequence as Anthropic / Gemini do — otherwise
     * persona behavior would diverge across backends.
     */
    fun buildMessages(
        input: String,
        systemPrompt: String,
        fewShots: List<FewShot>,
    ): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        if (systemPrompt.isNotBlank()) {
            out += ChatMessage("system", systemPrompt)
        }
        for (shot in fewShots) {
            out += ChatMessage("user", shot.userInput)
            out += ChatMessage("assistant", shot.assistantResponse)
        }
        out += ChatMessage("user", input)
        return out
    }

    fun ollamaBody(model: String, messages: List<ChatMessage>): String =
        JSON.encodeToString(OllamaChatRequest.serializer(), OllamaChatRequest(model = model, messages = messages))

    fun openAiBody(model: String, messages: List<ChatMessage>): String =
        JSON.encodeToString(OpenAiChatRequest.serializer(), OpenAiChatRequest(model = model, messages = messages))

    private val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }
}
