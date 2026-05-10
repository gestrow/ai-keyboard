// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.locallan

import com.aikeyboard.app.ai.client.AiStreamEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class OpenAiDelta(val content: String? = null)

@Serializable
internal data class OpenAiChoice(
    val delta: OpenAiDelta? = null,
    @kotlinx.serialization.SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
internal data class OpenAiChunk(val choices: List<OpenAiChoice> = emptyList())

internal object OpenAiCompatStreamParser {

    private val JSON = Json { ignoreUnknownKeys = true }
    private const val DATA_PREFIX = "data:"
    private const val DONE_SENTINEL = "[DONE]"

    /**
     * Parses a single SSE line ("data: {...}" or "data: [DONE]") and emits
     * AiStreamEvent. Caller iterates lines from Ktor's body channel.
     *
     * SSE comments (lines starting with ":") and empty lines are no-ops. Only
     * terminal finish_reason values ("stop", "length") emit Done — intermediate
     * values like "tool_calls" or "content_filter" can appear on chunks that
     * aren't the last one (especially on multi-step function-calling servers);
     * treating those as Done would terminate the stream early. The [DONE]
     * sentinel above is the universal closer; finish_reason is a fallback
     * for servers that omit it.
     */
    fun parseLine(line: String, emit: (AiStreamEvent) -> Unit) {
        val trimmed = line.trimStart()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return
        if (!trimmed.startsWith(DATA_PREFIX)) return
        val payload = trimmed.removePrefix(DATA_PREFIX).trimStart()
        if (payload.isEmpty()) return
        if (payload == DONE_SENTINEL) {
            emit(AiStreamEvent.Done)
            return
        }
        val chunk = runCatching { JSON.decodeFromString(OpenAiChunk.serializer(), payload) }.getOrNull()
            ?: return // malformed — skip; PRIVACY: don't log payload
        val first = chunk.choices.firstOrNull()
        val delta = first?.delta?.content
        if (!delta.isNullOrEmpty()) emit(AiStreamEvent.Delta(delta))
        val finish = first?.finishReason
        if (finish == "stop" || finish == "length") emit(AiStreamEvent.Done)
    }
}
