// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.locallan

import com.aikeyboard.app.ai.client.AiStreamEvent
import com.aikeyboard.app.ai.client.ErrorType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class OllamaChatStreamMessage(val role: String? = null, val content: String? = null)

@Serializable
internal data class OllamaChatStreamChunk(
    val message: OllamaChatStreamMessage? = null,
    val done: Boolean = false,
    val error: String? = null,
)

internal object OllamaStreamParser {

    private val JSON = Json { ignoreUnknownKeys = true }

    /**
     * Parses a single line of Ollama's `/api/chat` line-delimited JSON stream and
     * dispatches AiStreamEvent values via the callback. Caller iterates lines from
     * Ktor's body channel.
     *
     * Ollama's "error" field carries server-side strings that may include prompt
     * fragments; surface as a static structural message instead of echoing.
     */
    fun parseLine(line: String, emit: (AiStreamEvent) -> Unit) {
        if (line.isBlank()) return
        val chunk = runCatching { JSON.decodeFromString(OllamaChatStreamChunk.serializer(), line) }.getOrNull()
            ?: return // malformed line — skip silently; PRIVACY: don't log line contents
        if (chunk.error != null) {
            emit(AiStreamEvent.Error(ErrorType.NETWORK_FAILURE, "ollama-error"))
            return
        }
        val delta = chunk.message?.content
        if (!delta.isNullOrEmpty()) emit(AiStreamEvent.Delta(delta))
        if (chunk.done) emit(AiStreamEvent.Done)
    }
}
