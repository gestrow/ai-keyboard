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
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Speaks Anthropic Messages and Gemini streamGenerateContent over HTTPS.
 *
 * Phase 3a: request building is real and unit-tested; {@link #rewrite} returns a stub
 * that emits {@link ErrorType#NOT_IMPLEMENTED} (or {@link ErrorType#NO_API_KEY} when
 * the provider is unconfigured) so callers compile and run.
 *
 * Phase 3b: implement actual SSE streaming for Anthropic and chunked-JSON streaming
 * for Gemini against {@link #buildRequest}'s output.
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

        // Phase 3b: build the request, dispatch via httpClient, parse SSE / chunked JSON,
        // emit Delta events per token, Done on completion, Error with mapped ErrorType
        // on HTTP failure.
        emit(
            AiStreamEvent.Error(
                ErrorType.NOT_IMPLEMENTED,
                "Streaming not implemented yet (Phase 3b)",
            ),
        )
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
    }
}
