// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.locallan

import com.aikeyboard.app.ai.client.AiClient
import com.aikeyboard.app.ai.client.AiStreamEvent
import com.aikeyboard.app.ai.client.ErrorType
import com.aikeyboard.app.ai.client.termux.TermuxBridgeBackend
import com.aikeyboard.app.ai.persona.FewShot
import com.aikeyboard.app.latin.utils.Log
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Speaks to a user-configured Local LAN model server (Ollama, LM Studio, vLLM,
 * llama.cpp's server, etc.) over HTTP/HTTPS. Two API formats supported:
 *
 *  - Ollama-native (`POST <base>/api/chat`, line-delimited JSON streaming)
 *  - OpenAI-compatible (`POST <base>/v1/chat/completions`, SSE streaming)
 *
 * Auto-detection across self-hosted setups is failure-prone (LM Studio's
 * "OpenAI-compatible mode" is opt-in, Ollama's API is its native one); the
 * user picks. Reuses the long-socket-timeout HttpClient singleton from
 * TermuxBridgeBackend — instantiating a second client would just duplicate
 * the OkHttp engine + Ktor plugin chain for no benefit.
 */
class LocalLanBackend(
    private val baseUrl: String,
    private val apiFormat: LocalLanApiFormat,
    private val apiKey: String,
    private val modelName: String,
) : AiClient {

    override fun isAvailable(): Boolean =
        baseUrl.isNotEmpty() && modelName.isNotEmpty()

    override fun rewrite(
        input: String,
        systemPrompt: String,
        fewShots: List<FewShot>,
    ): Flow<AiStreamEvent> = callbackFlow {
        val messages = LocalLanRequestBuilder.buildMessages(input, systemPrompt, fewShots)
        try {
            when (apiFormat) {
                LocalLanApiFormat.OLLAMA -> streamOllama(this, messages)
                LocalLanApiFormat.OPENAI_COMPATIBLE -> streamOpenAi(this, messages)
            }
        } catch (te: TimeoutCancellationException) {
            // MUST come before CancellationException — TimeoutCancellationException IS-A
            // CancellationException, so the inverse silently drops timeouts. Same precedent
            // as RemoteApiBackend / TermuxBridgeBackend.
            trySend(AiStreamEvent.Error(ErrorType.TIMEOUT, "locallan-timeout"))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // PRIVACY: log type only — server-returned strings may include the prompt or
            // user input; t.message likewise can contain raw response fragments.
            Log.w(TAG, "LocalLan stream failed: ${t.javaClass.simpleName}")
            trySend(AiStreamEvent.Error(ErrorType.NETWORK_FAILURE, "locallan-failure"))
        }
        // OkHttp's coroutine engine cancels the underlying Call when the scope is
        // cancelled; the empty awaitClose body satisfies the callbackFlow contract.
        awaitClose { }
    }

    private suspend fun streamOllama(
        scope: ProducerScope<AiStreamEvent>,
        messages: List<ChatMessage>,
    ) {
        val url = baseUrl.trimEnd('/') + "/api/chat"
        val body = LocalLanRequestBuilder.ollamaBody(modelName, messages)
        TermuxBridgeBackend.httpClient.preparePost(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
            if (apiKey.isNotEmpty()) headers { append("Authorization", "Bearer $apiKey") }
        }.execute { response ->
            if (response.status != HttpStatusCode.OK) {
                scope.trySend(AiStreamEvent.Error(mapHttpError(response.status), "locallan-http"))
                return@execute
            }
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                OllamaStreamParser.parseLine(line) { scope.trySend(it) }
            }
        }
    }

    private suspend fun streamOpenAi(
        scope: ProducerScope<AiStreamEvent>,
        messages: List<ChatMessage>,
    ) {
        val url = baseUrl.trimEnd('/') + "/v1/chat/completions"
        val body = LocalLanRequestBuilder.openAiBody(modelName, messages)
        TermuxBridgeBackend.httpClient.preparePost(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
            if (apiKey.isNotEmpty()) headers { append("Authorization", "Bearer $apiKey") }
        }.execute { response ->
            if (response.status != HttpStatusCode.OK) {
                scope.trySend(AiStreamEvent.Error(mapHttpError(response.status), "locallan-http"))
                return@execute
            }
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                OpenAiCompatStreamParser.parseLine(line) { scope.trySend(it) }
            }
        }
    }

    private fun mapHttpError(status: HttpStatusCode): ErrorType = when (status.value) {
        401, 403 -> ErrorType.AUTH_FAILURE
        429 -> ErrorType.RATE_LIMITED
        in 500..599 -> ErrorType.NETWORK_FAILURE
        else -> ErrorType.UNKNOWN
    }

    companion object {
        private const val TAG = "LocalLanBackend"
    }
}
