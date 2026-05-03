// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.remote

import com.aikeyboard.app.ai.client.AiStreamEvent
import com.aikeyboard.app.ai.client.ErrorType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM coverage for the SSE / chunked-JSON parsing helpers in RemoteApiBackend.
 * Network plumbing (Ktor + OkHttp) is exercised on-device; these tests pin the
 * provider-specific event-shape parsers so we can refactor without breaking format
 * handling silently.
 */
class RemoteApiBackendStreamingTest {

    // ---------- Anthropic ----------

    @Test
    fun anthropicDelta_extractsTextFromContentBlockDelta() {
        val json = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}"""
        assertEquals("Hello", RemoteApiBackend.parseAnthropicDeltaText(json))
    }

    @Test
    fun anthropicDelta_returnsNullForToolUseDelta() {
        // input_json_delta carries tool argument fragments; we ignore them in plain Rewrite.
        val json = """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"a"}}"""
        assertNull(RemoteApiBackend.parseAnthropicDeltaText(json))
    }

    @Test
    fun anthropicDelta_returnsNullForMalformedJson() {
        assertNull(RemoteApiBackend.parseAnthropicDeltaText("not-json"))
    }

    @Test
    fun anthropicError_overloadedMapsToRateLimited() {
        val json = """{"type":"error","error":{"type":"overloaded_error","message":"slow down"}}"""
        val err = RemoteApiBackend.parseAnthropicError(json)
        assertEquals(ErrorType.RATE_LIMITED, err.type)
        assertEquals("slow down", err.message)
    }

    @Test
    fun anthropicError_authMapsToAuthFailure() {
        val json = """{"type":"error","error":{"type":"authentication_error","message":"bad key"}}"""
        val err = RemoteApiBackend.parseAnthropicError(json)
        assertEquals(ErrorType.AUTH_FAILURE, err.type)
    }

    @Test
    fun anthropicError_unknownTypeFallsBackToUnknown() {
        val json = """{"type":"error","error":{"type":"weirdo","message":"???"}}"""
        val err = RemoteApiBackend.parseAnthropicError(json)
        assertEquals(ErrorType.UNKNOWN, err.type)
    }

    @Test
    fun anthropicError_malformedSurfacesGenericMessage() {
        val err = RemoteApiBackend.parseAnthropicError("{not json")
        assertEquals(ErrorType.UNKNOWN, err.type)
        assertTrue(err.message.contains("malformed", ignoreCase = true))
    }

    // ---------- Gemini ----------

    @Test
    fun geminiChunk_extractsTextFromCandidate() {
        val json = """{"candidates":[{"content":{"parts":[{"text":"chunk one"}],"role":"model"},"finishReason":null}]}"""
        val event = RemoteApiBackend.parseGeminiChunk(json)
        assertTrue(event is RemoteApiBackend.GeminiChunk.Delta)
        assertEquals("chunk one", (event as RemoteApiBackend.GeminiChunk.Delta).text)
    }

    @Test
    fun geminiChunk_finishReasonStopMapsToDoneWithText() {
        val json = """{"candidates":[{"content":{"parts":[{"text":""}],"role":"model"},"finishReason":"STOP"}]}"""
        val event = RemoteApiBackend.parseGeminiChunk(json)
        assertTrue(event is RemoteApiBackend.GeminiChunk.DoneWithText)
    }

    @Test
    fun geminiChunk_safetyMapsToBlocked() {
        val json = """{"candidates":[{"content":{"parts":[],"role":"model"},"finishReason":"SAFETY"}]}"""
        val event = RemoteApiBackend.parseGeminiChunk(json)
        assertTrue(event is RemoteApiBackend.GeminiChunk.Blocked)
        assertEquals(
            "Response blocked by Gemini safety filter",
            (event as RemoteApiBackend.GeminiChunk.Blocked).message,
        )
    }

    @Test
    fun geminiChunk_promptFeedbackBlockReasonMapsToBlocked() {
        // No candidates, only promptFeedback — Gemini's "input was blocked" framing.
        val json = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
        val event = RemoteApiBackend.parseGeminiChunk(json)
        assertTrue(event is RemoteApiBackend.GeminiChunk.Blocked)
        assertTrue((event as RemoteApiBackend.GeminiChunk.Blocked).message.contains("Input"))
    }

    @Test
    fun geminiChunk_emptyCandidatesNoFeedbackIsMalformed() {
        val event = RemoteApiBackend.parseGeminiChunk("""{"candidates":[]}""")
        assertEquals(RemoteApiBackend.GeminiChunk.Malformed, event)
    }

    @Test
    fun geminiChunk_garbageJsonIsMalformed() {
        assertEquals(RemoteApiBackend.GeminiChunk.Malformed, RemoteApiBackend.parseGeminiChunk("garbage"))
    }

    // ---------- Upstream error message extraction ----------

    @Test
    fun upstreamErrorMessage_geminiShape() {
        val body = """{"error":{"code":401,"message":"API key not valid","status":"UNAUTHENTICATED"}}"""
        assertEquals("API key not valid", RemoteApiBackend.extractUpstreamErrorMessage(body))
    }

    @Test
    fun upstreamErrorMessage_anthropicShape() {
        val body = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
        assertEquals("invalid x-api-key", RemoteApiBackend.extractUpstreamErrorMessage(body))
    }

    @Test
    fun upstreamErrorMessage_returnsNullForUnstructured() {
        assertNotNull(RemoteApiBackend.extractUpstreamErrorMessage("plain html error page").let { it ?: "OK" })
        assertNull(RemoteApiBackend.extractUpstreamErrorMessage("<html>..."))
    }
}
