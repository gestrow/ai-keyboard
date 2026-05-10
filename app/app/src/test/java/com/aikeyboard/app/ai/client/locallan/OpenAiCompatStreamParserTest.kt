// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.locallan

import com.aikeyboard.app.ai.client.AiStreamEvent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiCompatStreamParserTest {

    private fun collect(line: String): List<AiStreamEvent> {
        val out = mutableListOf<AiStreamEvent>()
        OpenAiCompatStreamParser.parseLine(line) { out += it }
        return out
    }

    @Test fun dataLine_emitsDelta() {
        val events = collect("""data: {"choices":[{"delta":{"content":"Hi"},"finish_reason":null}]}""")
        assertEquals(1, events.size)
        assertEquals(AiStreamEvent.Delta("Hi"), events[0])
    }

    @Test fun doneSentinel_emitsDone() {
        val events = collect("data: [DONE]")
        assertEquals(1, events.size)
        assertEquals(AiStreamEvent.Done, events[0])
    }

    @Test fun finishReasonStop_emitsDone() {
        val events = collect(
            """data: {"choices":[{"delta":{"content":""},"finish_reason":"stop"}]}"""
        )
        assertEquals(1, events.size)
        assertEquals(AiStreamEvent.Done, events[0])
    }

    @Test fun finishReasonLength_emitsDone() {
        val events = collect(
            """data: {"choices":[{"delta":{"content":""},"finish_reason":"length"}]}"""
        )
        assertEquals(1, events.size)
        assertEquals(AiStreamEvent.Done, events[0])
    }

    @Test fun finishReasonToolCalls_doesNotEmitDone() {
        // Multi-step function-calling servers emit `tool_calls` on intermediate chunks;
        // closing the stream here would cut off the actual final response.
        val events = collect(
            """data: {"choices":[{"delta":{"content":"calling"},"finish_reason":"tool_calls"}]}"""
        )
        assertEquals(1, events.size)
        assertEquals(AiStreamEvent.Delta("calling"), events[0])
    }

    @Test fun sseComment_isNoOp() {
        // ":heartbeat" lines are SSE comments — skip without emitting anything.
        assertTrue(collect(": heartbeat").isEmpty())
    }

    @Test fun blankLine_isNoOp() {
        assertTrue(collect("").isEmpty())
    }

    @Test fun emptyDataPayload_isNoOp() {
        // "data: " with nothing after — some servers send these as keepalives.
        assertTrue(collect("data: ").isEmpty())
        assertTrue(collect("data:").isEmpty())
    }

    @Test fun malformedJson_isSilentlySkipped() {
        // PRIVACY: never log payload contents.
        assertTrue(collect("data: not json").isEmpty())
        assertTrue(collect("data: {truncated").isEmpty())
    }

    @Test fun nonDataPrefixedLine_isNoOp() {
        // SSE servers may emit `event: ...` framing lines we don't consume.
        assertTrue(collect("event: message").isEmpty())
        assertTrue(collect("id: 12345").isEmpty())
    }
}
