// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.termux

import com.aikeyboard.app.ai.client.AiStreamEvent
import com.aikeyboard.app.ai.client.ErrorType
import com.aikeyboard.app.ai.persona.FewShot
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM coverage for the bridge SSE parser, chat-body builder, and the
 * streaming loop in {@link TermuxBridgeBackend}. Network plumbing is not
 * exercised here — the real /chat HTTP call is verified on-device per
 * Phase 6's smoke test. These tests exist to lock down the wire shape
 * (envelope JSON, error-code mapping, forward-compat behavior) so future
 * refactors can't break it silently.
 */
class TermuxBridgeBackendTest {

    private val parser = Json { ignoreUnknownKeys = true }

    // ---------- buildChatBody ----------

    @Test
    fun buildChatBody_withSystemPromptAndFewShots_emitsAlternatingTurns() {
        val body = TermuxBridgeBackend.buildChatBody(
            cliProvider = "claude",
            input = "rewrite this",
            systemPrompt = "You are concise.",
            fewShots = listOf(
                FewShot(userInput = "u1", assistantResponse = "a1"),
                FewShot(userInput = "u2", assistantResponse = "a2"),
            ),
        )
        val json: JsonObject = parser.parseToJsonElement(body).jsonObject
        assertEquals("claude", json["provider"]?.jsonPrimitive?.contentOrNull)
        assertEquals("You are concise.", json["system"]?.jsonPrimitive?.contentOrNull)

        val messages: JsonArray = json["messages"]!!.jsonArray
        assertEquals(5, messages.size)  // 2 few-shots × 2 + final user
        assertEquals("user", messages[0].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("u1", messages[0].jsonObject["content"]?.jsonPrimitive?.contentOrNull)
        assertEquals("assistant", messages[1].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("a1", messages[1].jsonObject["content"]?.jsonPrimitive?.contentOrNull)
        assertEquals("user", messages[2].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("u2", messages[2].jsonObject["content"]?.jsonPrimitive?.contentOrNull)
        assertEquals("assistant", messages[3].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("a2", messages[3].jsonObject["content"]?.jsonPrimitive?.contentOrNull)
        assertEquals("user", messages[4].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("rewrite this", messages[4].jsonObject["content"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun buildChatBody_noSystemPrompt_omitsSystemField() {
        val body = TermuxBridgeBackend.buildChatBody("gemini", "hi", "", emptyList())
        val json = parser.parseToJsonElement(body).jsonObject
        assertNull(json["system"], "system field should be absent when prompt is empty")
        assertEquals("gemini", json["provider"]?.jsonPrimitive?.contentOrNull)
        // The user's input is the only message.
        val messages: JsonArray = json["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("hi", messages[0].jsonObject["content"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun buildChatBody_noFewShots_emitsOnlyTheUserInput() {
        val body = TermuxBridgeBackend.buildChatBody("claude", "hello", "sys", emptyList())
        val json = parser.parseToJsonElement(body).jsonObject
        val messages = json["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("hello", messages[0].jsonObject["content"]?.jsonPrimitive?.contentOrNull)
    }

    // ---------- parseEvent: deltas ----------

    @Test
    fun parseEvent_delta_extractsText() {
        val ev = TermuxBridgeBackend.parseEvent("""{"type":"delta","text":"hello"}""")
        assertTrue(ev is TermuxBridgeBackend.BridgeEvent.Delta)
        assertEquals("hello", (ev as TermuxBridgeBackend.BridgeEvent.Delta).text)
    }

    @Test
    fun parseEvent_deltaWithMissingText_returnsEmptyDelta() {
        val ev = TermuxBridgeBackend.parseEvent("""{"type":"delta"}""")
        assertTrue(ev is TermuxBridgeBackend.BridgeEvent.Delta)
        assertEquals("", (ev as TermuxBridgeBackend.BridgeEvent.Delta).text)
    }

    @Test
    fun parseEvent_done_returnsDone() {
        val ev = TermuxBridgeBackend.parseEvent("""{"type":"done"}""")
        assertEquals(TermuxBridgeBackend.BridgeEvent.Done, ev)
    }

    // ---------- parseEvent: error codes (full bridge code set) ----------

    @Test
    fun parseEvent_authFailure_mapsToAuthFailure() {
        val ev = TermuxBridgeBackend.parseEvent(
            """{"type":"error","code":"AUTH_FAILURE","message":"claude not authenticated"}""",
        )
        assertTrue(ev is TermuxBridgeBackend.BridgeEvent.Error)
        assertEquals(ErrorType.AUTH_FAILURE, (ev as TermuxBridgeBackend.BridgeEvent.Error).type)
    }

    @Test
    fun parseEvent_cliNotFound_mapsToNetworkFailure() {
        val ev = TermuxBridgeBackend.parseEvent(
            """{"type":"error","code":"CLI_NOT_FOUND","message":"claude binary missing"}""",
        )
        assertTrue(ev is TermuxBridgeBackend.BridgeEvent.Error)
        assertEquals(ErrorType.NETWORK_FAILURE, (ev as TermuxBridgeBackend.BridgeEvent.Error).type)
    }

    @Test
    fun parseEvent_rateLimited_mapsToRateLimited() {
        val ev = TermuxBridgeBackend.parseEvent(
            """{"type":"error","code":"RATE_LIMITED","message":"slow down"}""",
        )
        assertTrue(ev is TermuxBridgeBackend.BridgeEvent.Error)
        assertEquals(ErrorType.RATE_LIMITED, (ev as TermuxBridgeBackend.BridgeEvent.Error).type)
    }

    @Test
    fun parseEvent_timeout_mapsToTimeout() {
        val ev = TermuxBridgeBackend.parseEvent(
            """{"type":"error","code":"TIMEOUT","message":"cli stalled"}""",
        )
        assertTrue(ev is TermuxBridgeBackend.BridgeEvent.Error)
        assertEquals(ErrorType.TIMEOUT, (ev as TermuxBridgeBackend.BridgeEvent.Error).type)
    }

    @Test
    fun parseEvent_cliCrashed_mapsToNetworkFailure() {
        val ev = TermuxBridgeBackend.parseEvent(
            """{"type":"error","code":"CLI_CRASHED","message":"subprocess died"}""",
        )
        assertTrue(ev is TermuxBridgeBackend.BridgeEvent.Error)
        assertEquals(ErrorType.NETWORK_FAILURE, (ev as TermuxBridgeBackend.BridgeEvent.Error).type)
    }

    @Test
    fun parseEvent_unknownErrorCode_mapsToUnknown() {
        val ev = TermuxBridgeBackend.parseEvent(
            """{"type":"error","code":"WAT_IS_THIS","message":"???"}""",
        )
        assertTrue(ev is TermuxBridgeBackend.BridgeEvent.Error)
        assertEquals(ErrorType.UNKNOWN, (ev as TermuxBridgeBackend.BridgeEvent.Error).type)
    }

    // ---------- parseEvent: forward-compat + privacy ----------

    @Test
    fun parseEvent_unknownEventType_returnsUnknown() {
        // Forward-compat: bridge can introduce new event types without breaking the IME.
        val ev = TermuxBridgeBackend.parseEvent("""{"type":"unknown_future_event","data":42}""")
        assertEquals(TermuxBridgeBackend.BridgeEvent.Unknown, ev)
    }

    @Test
    fun parseEvent_malformedJson_returnsUnknown() {
        // Privacy: malformed input must not propagate as an exception whose `message` could
        // contain raw `data:` line content (model output / auth message).
        val ev = TermuxBridgeBackend.parseEvent("not json at all")
        assertEquals(TermuxBridgeBackend.BridgeEvent.Unknown, ev)
    }

    @Test
    fun parseEvent_emptyObject_returnsUnknown() {
        val ev = TermuxBridgeBackend.parseEvent("{}")
        assertEquals(TermuxBridgeBackend.BridgeEvent.Unknown, ev)
    }

    // ---------- streamBridgeSseAsFlow: end-to-end loop ----------

    @Test
    fun streamBridgeSse_happyPath_emitsDeltasThenDone() = runBlocking {
        val raw = """
            data: {"type":"delta","text":"hello"}

            data: {"type":"delta","text":" world"}

            data: {"type":"done"}

        """.trimIndent().toByteArray(Charsets.UTF_8)
        val events = TermuxBridgeBackend.streamBridgeSseAsFlow(ByteReadChannel(raw)).toList()
        assertEquals(3, events.size)
        assertEquals(AiStreamEvent.Delta("hello"), events[0])
        assertEquals(AiStreamEvent.Delta(" world"), events[1])
        assertEquals(AiStreamEvent.Done, events[2])
    }

    @Test
    fun streamBridgeSse_errorMidStream_terminatesAfterError() = runBlocking {
        val raw = """
            data: {"type":"delta","text":"partial"}

            data: {"type":"error","code":"AUTH_FAILURE","message":"login expired"}

            data: {"type":"delta","text":"should not appear"}

        """.trimIndent().toByteArray(Charsets.UTF_8)
        val events = TermuxBridgeBackend.streamBridgeSseAsFlow(ByteReadChannel(raw)).toList()
        assertEquals(2, events.size)
        assertEquals(AiStreamEvent.Delta("partial"), events[0])
        assertTrue(events[1] is AiStreamEvent.Error)
        assertEquals(ErrorType.AUTH_FAILURE, (events[1] as AiStreamEvent.Error).type)
    }

    @Test
    fun streamBridgeSse_streamCutWithoutDone_synthesizesDone() = runBlocking {
        val raw = """
            data: {"type":"delta","text":"only chunk"}

        """.trimIndent().toByteArray(Charsets.UTF_8)
        val events = TermuxBridgeBackend.streamBridgeSseAsFlow(ByteReadChannel(raw)).toList()
        assertEquals(2, events.size)
        assertEquals(AiStreamEvent.Delta("only chunk"), events[0])
        assertEquals(AiStreamEvent.Done, events[1])
    }

    @Test
    fun streamBridgeSse_skipsBlankAndUnknownLines() = runBlocking {
        // The bridge could emit `: keepalive` SSE comments or stray empty lines;
        // the loop must filter them silently rather than mis-parsing.
        val raw = """
            : keepalive comment

            data: {"type":"delta","text":"x"}

            data: {"type":"unknown_future_event"}

            data: {"type":"done"}

        """.trimIndent().toByteArray(Charsets.UTF_8)
        val events = TermuxBridgeBackend.streamBridgeSseAsFlow(ByteReadChannel(raw)).toList()
        assertEquals(2, events.size)
        assertEquals(AiStreamEvent.Delta("x"), events[0])
        assertEquals(AiStreamEvent.Done, events[1])
    }

    @Test
    fun streamBridgeSse_emptyDeltasAreSkipped() = runBlocking {
        val raw = """
            data: {"type":"delta","text":""}

            data: {"type":"delta","text":"real"}

            data: {"type":"done"}

        """.trimIndent().toByteArray(Charsets.UTF_8)
        val events = TermuxBridgeBackend.streamBridgeSseAsFlow(ByteReadChannel(raw)).toList()
        assertEquals(2, events.size)
        assertEquals(AiStreamEvent.Delta("real"), events[0])
        assertEquals(AiStreamEvent.Done, events[1])
    }
}
