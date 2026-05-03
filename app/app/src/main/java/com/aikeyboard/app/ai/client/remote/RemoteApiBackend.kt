// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.remote

import com.aikeyboard.app.ai.client.AiClient
import com.aikeyboard.app.ai.client.AiStreamEvent
import com.aikeyboard.app.ai.client.ErrorType
import com.aikeyboard.app.ai.client.Provider
import com.aikeyboard.app.ai.persona.FewShot
import com.aikeyboard.app.ai.storage.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Speaks Anthropic Messages and Gemini streamGenerateContent over HTTPS.
 *
 * Phase 3a built request shapes; 3b streams the responses. Anthropic uses named SSE
 * events (`event: content_block_delta` etc.); Gemini's `?alt=sse` framing emits
 * data-only events with one JSON object per chunk. Both branches map HTTP failures
 * and exceptions to specific {@link ErrorType}s before completing the flow.
 */
class RemoteApiBackend(
    private val provider: Provider,
    private val secureStorage: SecureStorage,
    private val model: String = provider.defaultModel,
) : AiClient {

    override fun isAvailable(): Boolean = secureStorage.getApiKey(provider) != null

    override fun rewrite(
        input: String,
        systemPrompt: String,
        fewShots: List<FewShot>,
    ): Flow<AiStreamEvent> = flow {
        val key = secureStorage.getApiKey(provider)
        if (key.isNullOrEmpty()) {
            emit(
                AiStreamEvent.Error(
                    ErrorType.NO_API_KEY,
                    "${provider.displayName} API key not configured",
                ),
            )
            return@flow
        }

        val request = buildRequest(input, systemPrompt, fewShots)

        try {
            httpClient.preparePost(request.url) {
                contentType(ContentType.Application.Json)
                headers {
                    request.headers.forEach { (k, v) -> append(k, v) }
                }
                setBody(request.jsonBody)
            }.execute { response ->
                val status = response.status
                if (status.value !in 200..299) {
                    emit(mapHttpError(status, response))
                    return@execute
                }
                when (provider) {
                    Provider.ANTHROPIC -> streamAnthropic(response.bodyAsChannel())
                    Provider.GOOGLE_GEMINI -> streamGemini(response.bodyAsChannel())
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (te: TimeoutCancellationException) {
            emit(AiStreamEvent.Error(ErrorType.TIMEOUT, "Request timed out"))
        } catch (t: Throwable) {
            emit(
                AiStreamEvent.Error(
                    ErrorType.NETWORK_FAILURE,
                    "Network error: ${t.message ?: t.javaClass.simpleName}",
                ),
            )
        }
    }

    private suspend fun FlowCollector<AiStreamEvent>.streamAnthropic(channel: ByteReadChannel) {
        // Track the most recent `event:` name so the matching `data:` line can be dispatched.
        // Events are blank-line-terminated; ping events have no data and we ignore them.
        var currentEvent: String? = null
        while (true) {
            val rawLine = channel.readUTF8Line() ?: break
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty()) {
                currentEvent = null
                continue
            }
            when {
                line.startsWith("event:") -> currentEvent = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    when (currentEvent) {
                        "content_block_delta" -> {
                            val text = parseAnthropicDeltaText(data)
                            if (!text.isNullOrEmpty()) emit(AiStreamEvent.Delta(text))
                        }
                        "message_stop" -> {
                            emit(AiStreamEvent.Done)
                            return
                        }
                        "error" -> {
                            emit(parseAnthropicError(data))
                            return
                        }
                        // message_start, message_delta, content_block_start/stop, ping → ignore
                        else -> Unit
                    }
                }
                // SSE comments (`: keepalive`) and unknown line prefixes are ignored.
            }
        }
        // Stream ended without an explicit message_stop event. Treat as done so the user
        // sees what was streamed rather than an error.
        emit(AiStreamEvent.Done)
    }

    private suspend fun FlowCollector<AiStreamEvent>.streamGemini(channel: ByteReadChannel) {
        // Gemini's `?alt=sse` framing emits only `data:` lines, each a complete JSON object.
        while (true) {
            val rawLine = channel.readUTF8Line() ?: break
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty() || !line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue

            when (val event = parseGeminiChunk(data)) {
                is GeminiChunk.Delta -> if (event.text.isNotEmpty()) emit(AiStreamEvent.Delta(event.text))
                is GeminiChunk.DoneWithText -> {
                    if (event.text.isNotEmpty()) emit(AiStreamEvent.Delta(event.text))
                    emit(AiStreamEvent.Done)
                    return
                }
                GeminiChunk.Done -> {
                    emit(AiStreamEvent.Done)
                    return
                }
                is GeminiChunk.Blocked -> {
                    emit(AiStreamEvent.Error(ErrorType.UNKNOWN, event.message))
                    return
                }
                GeminiChunk.Malformed -> {
                    emit(AiStreamEvent.Error(ErrorType.UNKNOWN, "Malformed response from Gemini"))
                    return
                }
            }
        }
        emit(AiStreamEvent.Done)
    }

    private suspend fun mapHttpError(status: HttpStatusCode, response: HttpResponse): AiStreamEvent.Error {
        val type = when (status.value) {
            401, 403 -> ErrorType.AUTH_FAILURE
            429 -> ErrorType.RATE_LIMITED
            in 500..599 -> ErrorType.NETWORK_FAILURE
            else -> ErrorType.UNKNOWN
        }
        // Gemini surfaces `error.message`; Anthropic mirrors the same shape on non-streaming errors.
        // Surfacing the upstream message gives clearer guidance ("API key not valid"), which is the
        // model-supplied error string, not user content. Body itself is *not* logged.
        val upstreamMessage = runCatching {
            extractUpstreamErrorMessage(response.readBodyTruncated())
        }.getOrNull()
        val message = when {
            !upstreamMessage.isNullOrBlank() -> "${provider.displayName}: $upstreamMessage"
            type == ErrorType.AUTH_FAILURE -> "${provider.displayName} API key invalid or expired"
            type == ErrorType.RATE_LIMITED -> "${provider.displayName} rate limit hit; try again in a moment"
            type == ErrorType.NETWORK_FAILURE -> "${provider.displayName} service unavailable; try again"
            else -> "${provider.displayName} request failed (${status.value})"
        }
        return AiStreamEvent.Error(type, message)
    }

    fun buildRequest(input: String, systemPrompt: String, fewShots: List<FewShot>): RemoteApiRequest {
        val key = secureStorage.getApiKey(provider)
            ?: error("buildRequest called without an API key configured for ${provider.storageKey}")
        return when (provider) {
            Provider.ANTHROPIC -> buildAnthropicRequest(input, systemPrompt, fewShots, key, model)
            Provider.GOOGLE_GEMINI -> buildGeminiRequest(input, systemPrompt, fewShots, key, model)
        }
    }

    companion object {
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val ANTHROPIC_MAX_TOKENS = 4096
        private const val ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
        private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models"

        private val parser = Json { ignoreUnknownKeys = true }

        // Long socket timeout because the connection stays open for the duration of the stream.
        val httpClient: HttpClient by lazy {
            HttpClient(OkHttp) {
                expectSuccess = false
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
                install(HttpTimeout) {
                    connectTimeoutMillis = 30_000
                    socketTimeoutMillis = 60_000
                    requestTimeoutMillis = 60_000
                }
            }
        }

        internal fun buildAnthropicRequest(
            input: String,
            systemPrompt: String,
            fewShots: List<FewShot>,
            apiKey: String,
            model: String,
        ): RemoteApiRequest {
            val messages: JsonArray = buildJsonArray {
                fewShots.forEach { shot ->
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", shot.userInput)
                    })
                    add(buildJsonObject {
                        put("role", "assistant")
                        put("content", shot.assistantResponse)
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    put("content", input)
                })
            }
            val body: JsonObject = buildJsonObject {
                put("model", model)
                put("max_tokens", ANTHROPIC_MAX_TOKENS)
                put("stream", true)
                if (systemPrompt.isNotEmpty()) put("system", systemPrompt)
                put("messages", messages)
            }
            return RemoteApiRequest(
                url = ANTHROPIC_URL,
                headers = mapOf(
                    "x-api-key" to apiKey,
                    "anthropic-version" to ANTHROPIC_VERSION,
                    "content-type" to "application/json",
                    "accept" to "text/event-stream",
                ),
                jsonBody = Json.encodeToString(JsonObject.serializer(), body),
            )
        }

        internal fun buildGeminiRequest(
            input: String,
            systemPrompt: String,
            fewShots: List<FewShot>,
            apiKey: String,
            model: String,
        ): RemoteApiRequest {
            val contents: JsonArray = buildJsonArray {
                fewShots.forEach { shot ->
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", shot.userInput) }) })
                    })
                    add(buildJsonObject {
                        put("role", "model")
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", shot.assistantResponse) }) })
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", input) }) })
                })
            }
            val body: JsonObject = buildJsonObject {
                put("contents", contents)
                if (systemPrompt.isNotEmpty()) {
                    put("systemInstruction", buildJsonObject {
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", JsonPrimitive(systemPrompt)) })
                        })
                    })
                }
            }
            // Gemini takes the API key in the query string for streamGenerateContent; alt=sse
            // requests a server-sent-event framing of the chunked response.
            val url = "$GEMINI_BASE/$model:streamGenerateContent?alt=sse&key=$apiKey"
            return RemoteApiRequest(
                url = url,
                headers = mapOf(
                    "content-type" to "application/json",
                    "accept" to "text/event-stream",
                ),
                jsonBody = Json.encodeToString(JsonObject.serializer(), body),
            )
        }

        internal fun parseAnthropicDeltaText(data: String): String? {
            val parsed = runCatching { parser.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
            val inner = parsed["delta"]?.jsonObject ?: return null
            // Tool-use deltas (`input_json_delta`) appear if we ever add tools; ignore for plain text.
            if (inner["type"]?.jsonPrimitive?.contentOrNull != "text_delta") return null
            return inner["text"]?.jsonPrimitive?.contentOrNull
        }

        internal fun parseAnthropicError(data: String): AiStreamEvent.Error {
            val parsed = runCatching { parser.parseToJsonElement(data).jsonObject }.getOrNull()
                ?: return AiStreamEvent.Error(ErrorType.UNKNOWN, "Anthropic error (malformed)")
            val errObj = parsed["error"]?.jsonObject
            val innerType = errObj?.get("type")?.jsonPrimitive?.contentOrNull
            val message = errObj?.get("message")?.jsonPrimitive?.contentOrNull ?: "Anthropic error"
            val mapped = when (innerType) {
                "overloaded_error", "rate_limit_error" -> ErrorType.RATE_LIMITED
                "authentication_error", "permission_error" -> ErrorType.AUTH_FAILURE
                else -> ErrorType.UNKNOWN
            }
            return AiStreamEvent.Error(mapped, message)
        }

        internal fun parseGeminiChunk(data: String): GeminiChunk {
            val parsed = runCatching { parser.parseToJsonElement(data).jsonObject }.getOrNull()
                ?: return GeminiChunk.Malformed
            val candidates = parsed["candidates"]?.jsonArray
            if (candidates.isNullOrEmpty()) {
                val blockReason = parsed["promptFeedback"]?.jsonObject
                    ?.get("blockReason")?.jsonPrimitive?.contentOrNull
                if (!blockReason.isNullOrEmpty()) {
                    return GeminiChunk.Blocked("Input blocked by Gemini safety filter")
                }
                return GeminiChunk.Malformed
            }
            val first = candidates[0].jsonObject
            val finishReason = first["finishReason"]?.jsonPrimitive?.contentOrNull
            val text = first["content"]?.jsonObject
                ?.get("parts")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.contentOrNull
                .orEmpty()
            return when (finishReason) {
                null -> GeminiChunk.Delta(text)
                "STOP", "MAX_TOKENS" -> GeminiChunk.DoneWithText(text)
                "SAFETY", "RECITATION", "BLOCKED" ->
                    GeminiChunk.Blocked("Response blocked by Gemini safety filter")
                else -> GeminiChunk.Blocked("Gemini stopped: $finishReason")
            }
        }

        internal fun extractUpstreamErrorMessage(body: String): String? {
            val parsed = runCatching { parser.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return null
            // Gemini: { "error": { "message": "..." } }
            // Anthropic non-streaming errors mirror the same shape via {error:{type,message}}.
            val errObj = parsed["error"]?.jsonObject ?: return null
            return errObj["message"]?.jsonPrimitive?.contentOrNull
        }

        // Reads up to 8 KiB of the response body for surfacing an error message. Truncating keeps
        // the cost bounded and matches what users would meaningfully read in a single error toast.
        private suspend fun HttpResponse.readBodyTruncated(): String {
            val channel = bodyAsChannel()
            val sb = StringBuilder()
            while (sb.length < 8192) {
                val line = channel.readUTF8Line() ?: break
                sb.append(line).append('\n')
            }
            return sb.toString()
        }
    }

    internal sealed interface GeminiChunk {
        data class Delta(val text: String) : GeminiChunk
        // STOP / MAX_TOKENS chunks may carry trailing text that should be flushed before Done.
        data class DoneWithText(val text: String) : GeminiChunk
        data object Done : GeminiChunk
        data class Blocked(val message: String) : GeminiChunk
        data object Malformed : GeminiChunk
    }
}
