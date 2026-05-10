// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.locallan

import com.aikeyboard.app.ai.client.AiStreamEvent
import com.aikeyboard.app.ai.client.ErrorType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OllamaStreamParserTest {

    private fun collect(line: String): List<AiStreamEvent> {
        val out = mutableListOf<AiStreamEvent>()
        OllamaStreamParser.parseLine(line) { out += it }
        return out
    }

    @Test fun deltaChunk_emitsDelta() {
        val events = collect("""{"message":{"role":"assistant","content":"Hello"},"done":false}""")
        assertEquals(1, events.size)
        assertEquals(AiStreamEvent.Delta("Hello"), events[0])
    }

    @Test fun doneChunk_emitsDone() {
        // Final chunk: empty content + done=true (Ollama's terminal frame may also carry usage stats).
        val events = collect("""{"message":{"role":"assistant","content":""},"done":true}""")
        assertEquals(1, events.size)
        assertEquals(AiStreamEvent.Done, events[0])
    }

    @Test fun errorChunk_emitsErrorWithStaticMessage() {
        // PRIVACY: server-supplied error string MUST NOT be echoed back — verifies the
        // static "ollama-error" sentinel rather than the server's possibly-sensitive text.
        val events = collect("""{"error":"prompt 'rewrite this private email about ...' failed: model not found"}""")
        assertEquals(1, events.size)
        val err = events[0] as AiStreamEvent.Error
        assertEquals(ErrorType.NETWORK_FAILURE, err.type)
        assertEquals("ollama-error", err.message)
        // Defensive: nothing in the emitted message resembles the server text.
        assertTrue(!err.message.contains("private email"))
    }

    @Test fun blankLine_isNoOp() {
        assertTrue(collect("").isEmpty())
        assertTrue(collect("   ").isEmpty())
    }

    @Test fun malformedJson_isSilentlySkipped() {
        // PRIVACY: never log line contents — verify by emitting nothing.
        assertTrue(collect("not json").isEmpty())
        assertTrue(collect("{ truncated").isEmpty())
    }
}
