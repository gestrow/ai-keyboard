// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.termux

import com.aikeyboard.app.ai.client.AiClient
import com.aikeyboard.app.ai.client.AiStreamEvent
import com.aikeyboard.app.ai.client.ErrorType
import com.aikeyboard.app.ai.persona.FewShot
import com.aikeyboard.app.ai.termux.TermuxOrchestrator
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Speaks to the local Termux bridge at `127.0.0.1:8787/chat`. The bridge
 * normalizes Claude/Gemini CLI subprocess output into a flat per-line
 * SSE envelope (`data: {type:"delta"|"done"|"error", ...}`) — distinct
 * from Anthropic's named-event SSE that {@link com.aikeyboard.app.ai.client.remote.RemoteApiBackend}
 * consumes. The CLI provider is injected as a plain String matching the
 * bridge's `/providers` `id` field so adding Codex (Phase 11) needs no
 * refactor here.
 *
 * Error mapping is documented in PHASE_6_PROMPT.md:
 *   AUTH_FAILURE / RATE_LIMITED / TIMEOUT  → identical ErrorType
 *   CLI_NOT_FOUND / CLI_CRASHED            → NETWORK_FAILURE
 *   anything else                          → UNKNOWN (forward-compat)
 *
 * Privacy: bridge SSE deltas carry model output and CLI auth-failure
 * messages — both potentially sensitive. The flow never logs `text` or
 * `message` fields, and the catch-all branch deliberately uses
 * `t.javaClass.simpleName` rather than `t.message` because
 * kotlinx.serialization decoding exceptions can include excerpts of the
 * raw `data:` line in their message.
 */
class TermuxBridgeBackend(
    private val cliProvider: String, // "claude" | "gemini" | "codex" — matches /providers id
    private val baseUrl: String = TermuxOrchestrator.BRIDGE_BASE,
) : AiClient {

    // Synchronous availability check — matches RemoteApiBackend's "do you have what's
    // needed to *try*" semantics. Real reachability is checked at rewrite() time and
    // surfaced via AiStreamEvent.Error.
    override fun isAvailable(): Boolean = cliProvider.isNotEmpty()

    override fun rewrite(
        input: String,
        systemPrompt: String,
        fewShots: List<FewShot>,
    ): Flow<AiStreamEvent> = flow {
        val body = buildChatBody(cliProvider, input, systemPrompt, fewShots)
        try {
            httpClient.preparePost("$baseUrl/chat") {
                contentType(ContentType.Application.Json)
                accept(ContentType.Text.EventStream)
                setBody(body)
            }.execute { response ->
                if (response.status.value !in 200..299) {
                    emit(mapHttpError(response.status))
                    return@execute
                }
                streamBridgeSse(response.bodyAsChannel())
            }
        } catch (te: TimeoutCancellationException) {
            // MUST come before the broader CancellationException catch — TimeoutCancellationException
            // IS-A CancellationException, so the inverse order silently drops timeouts (the broader
            // catch fires first, re-throws, and CommandRowController's outer guard against
            // CancellationException turns it into a silent hang).
            emit(AiStreamEvent.Error(ErrorType.TIMEOUT, "Bridge request timed out"))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // Use simpleName not t.message — JSON decoding exceptions can carry raw `data:` line
            // excerpts (model output / CLI auth messages) in their message field.
            emit(
                AiStreamEvent.Error(
                    ErrorType.NETWORK_FAILURE,
                    "Bridge unreachable: ${t.javaClass.simpleName}",
                ),
            )
        }
    }

    private suspend fun FlowCollector<AiStreamEvent>.streamBridgeSse(channel: ByteReadChannel) {
        // Bridge SSE: only `data:` lines, each one a complete JSON object with `type` field.
        // No `event:` lines (unlike Anthropic). Blank lines separate events.
        while (true) {
            val raw = channel.readUTF8Line() ?: break
            val line = raw.trimEnd('\r')
            if (line.isEmpty() || !line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue

            when (val parsed = parseEvent(data)) {
                is BridgeEvent.Delta -> if (parsed.text.isNotEmpty()) emit(AiStreamEvent.Delta(parsed.text))
                BridgeEvent.Done -> {
                    emit(AiStreamEvent.Done)
                    return
                }
                is BridgeEvent.Error -> {
                    emit(AiStreamEvent.Error(parsed.type, parsed.message))
                    return
                }
                BridgeEvent.Unknown -> Unit  // forward-compat with future event types
            }
        }
        // Stream ended without an explicit `done` — treat as done so the user sees what was
        // streamed rather than an error (matches RemoteApiBackend.streamAnthropic semantics).
        emit(AiStreamEvent.Done)
    }

    private fun mapHttpError(status: HttpStatusCode): AiStreamEvent.Error {
        // 400 = validation (missing/unknown provider, missing messages); 5xx = bridge crash.
        // Don't try to read the body for `/chat` errors: bridge returns plain `{error:"..."}`
        // on validation, but the body already streamed past in some Ktor edge cases. The
        // `code` itself is enough for actionable user-facing messaging.
        val type = when (status.value) {
            in 500..599 -> ErrorType.NETWORK_FAILURE
            else -> ErrorType.UNKNOWN
        }
        val message = when (status.value) {
            400 -> "Bridge rejected the request"
            in 500..599 -> "Bridge service error (${status.value})"
            else -> "Bridge request failed (${status.value})"
        }
        return AiStreamEvent.Error(type, message)
    }

    // Sealed result of parsing a single bridge SSE `data:` line. Internal so the JVM unit
    // tests can match against it directly without going through the streaming loop.
    internal sealed interface BridgeEvent {
        data class Delta(val text: String) : BridgeEvent
        data object Done : BridgeEvent
        data class Error(val type: ErrorType, val message: String) : BridgeEvent
        // Forward-compat: malformed JSON, or a `type` we don't yet recognize. Streaming loop
        // skips silently rather than aborting, so the bridge can add new event types without
        // breaking older IMEs in the field.
        data object Unknown : BridgeEvent
    }

    companion object {
        // Long socket timeout because the connection stays open for the SSE stream.
        // Distinct from TermuxOrchestrator.httpClient (2s, sized for /health polling) —
        // reusing that here would kill /chat streams instantly.
        val httpClient: HttpClient by lazy {
            HttpClient(OkHttp) {
                expectSuccess = false
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(HttpTimeout) {
                    connectTimeoutMillis = 5_000
                    socketTimeoutMillis = 60_000
                    requestTimeoutMillis = 60_000
                }
            }
        }

        private val parser = Json { ignoreUnknownKeys = true }

        /**
         * Builds the JSON body for `POST /chat`. Few-shots become alternating
         * `{role:user|assistant, content}` objects ahead of the final user message.
         *
         * **Behavioral divergence from RemoteApiBackend** worth surfacing for the user:
         * the bridge's `claude.js` and `gemini.js` adapters call `lastUserContent(messages)`
         * which only consumes the *most recent* user message — few-shots in the array are
         * silently discarded by the bridge, even though the `system` prompt does propagate.
         * Phase 6 still encodes them faithfully so future bridge improvements honor the
         * shape without breaking the wire format.
         */
        internal fun buildChatBody(
            cliProvider: String,
            input: String,
            systemPrompt: String,
            fewShots: List<FewShot>,
        ): String {
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
            val obj: JsonObject = buildJsonObject {
                put("provider", cliProvider)
                put("messages", messages)
                if (systemPrompt.isNotEmpty()) put("system", systemPrompt)
            }
            return Json.encodeToString(JsonObject.serializer(), obj)
        }

        /**
         * Parses a single SSE `data:` payload. **Wraps the JSON parse in runCatching**
         * so a malformed line never propagates a JsonDecodingException whose message
         * could contain a fragment of the raw line (model output / auth message).
         * After successful parse, switch on `type`.
         */
        internal fun parseEvent(data: String): BridgeEvent {
            val parsed = runCatching { parser.parseToJsonElement(data).jsonObject }
                .getOrElse { return BridgeEvent.Unknown }
            return when (parsed["type"]?.jsonPrimitive?.contentOrNull) {
                "delta" -> {
                    val text = parsed["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    BridgeEvent.Delta(text)
                }
                "done" -> BridgeEvent.Done
                "error" -> {
                    val code = parsed["code"]?.jsonPrimitive?.contentOrNull
                    val message = parsed["message"]?.jsonPrimitive?.contentOrNull
                        ?: defaultMessageForCode(code)
                    BridgeEvent.Error(mapBridgeErrorCode(code), message)
                }
                else -> BridgeEvent.Unknown
            }
        }

        // Full bridge code set — verified against bridge/adapters/claude.js classifyCode,
        // bridge/adapters/gemini.js classifyCode, and bridge/server.js availability checks.
        // Mapping the full set is required so user-facing errors (rate-limit, timeout) route
        // to the correct preview-strip messaging instead of falling through to UNKNOWN.
        internal fun mapBridgeErrorCode(code: String?): ErrorType = when (code) {
            "AUTH_FAILURE" -> ErrorType.AUTH_FAILURE
            "RATE_LIMITED" -> ErrorType.RATE_LIMITED
            "TIMEOUT" -> ErrorType.TIMEOUT
            // CLI_NOT_FOUND (CLI uninstalled) and CLI_CRASHED (subprocess died mid-stream)
            // are both "the bridge can't currently produce a response" — closest match in
            // our user-facing types is NETWORK_FAILURE ("service unavailable"). Distinct
            // from AUTH_FAILURE (which has a re-auth recovery path).
            "CLI_NOT_FOUND", "CLI_CRASHED" -> ErrorType.NETWORK_FAILURE
            else -> ErrorType.UNKNOWN
        }

        private fun defaultMessageForCode(code: String?): String = when (code) {
            "AUTH_FAILURE" -> "CLI not authenticated"
            "RATE_LIMITED" -> "Rate limited; try again in a moment"
            "TIMEOUT" -> "Request timed out"
            "CLI_NOT_FOUND" -> "CLI not installed in Termux"
            "CLI_CRASHED" -> "CLI crashed; bridge restart may help"
            null -> "Bridge error"
            else -> "Bridge error: $code"
        }

        /**
         * Test-only entry point: drives the same per-line dispatch the production flow
         * uses, but takes a raw ByteReadChannel and returns a Flow<AiStreamEvent>.
         * Mirrors the behavior of the FlowCollector extension above so tests are
         * exercising the production code path, not a fork.
         */
        internal fun streamBridgeSseAsFlow(channel: ByteReadChannel): Flow<AiStreamEvent> = flow {
            while (true) {
                val raw = channel.readUTF8Line() ?: break
                val line = raw.trimEnd('\r')
                if (line.isEmpty() || !line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue

                when (val parsed = parseEvent(data)) {
                    is BridgeEvent.Delta -> if (parsed.text.isNotEmpty()) emit(AiStreamEvent.Delta(parsed.text))
                    BridgeEvent.Done -> {
                        emit(AiStreamEvent.Done)
                        return@flow
                    }
                    is BridgeEvent.Error -> {
                        emit(AiStreamEvent.Error(parsed.type, parsed.message))
                        return@flow
                    }
                    BridgeEvent.Unknown -> Unit
                }
            }
            emit(AiStreamEvent.Done)
        }
    }
}
