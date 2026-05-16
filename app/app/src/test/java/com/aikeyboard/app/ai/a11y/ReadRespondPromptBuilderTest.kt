// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.a11y

import com.aikeyboard.app.ai.persona.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 7b: pure-JVM coverage of the ReadRespondPromptBuilder contract.
 * The builder is the only place where screen content gets stitched into a
 * prompt — these tests pin the truncation rule and the two-branch shape
 * (with/without uncommitted hint) so a future regression doesn't quietly
 * change what gets sent to the backend.
 */
class ReadRespondPromptBuilderTest {

    private val persona = Persona(
        id = "p-test",
        name = "Tester",
        systemPrompt = "You are a helpful tester.",
    )

    @Test
    fun `short context with empty focusedInputText emits Compose branch`() {
        val above = "Hi from Dana,\nCan you grab milk?"

        val (input, systemPrompt) = ReadRespondPromptBuilder.build(
            aboveInputText = above,
            focusedInputText = "",
            persona = persona,
        )

        assertEquals("You are a helpful tester.", systemPrompt)
        assertTrue(
            "expected screen-content header in input, got: $input",
            input.startsWith("I'm reading this on my screen:\n---\n"),
        )
        assertTrue(input.contains(above))
        assertTrue(input.contains("Respond to the latest message above in the Tester voice."))
        assertFalse(input.contains("Continue or rewrite my response in the"))
        assertTrue(input.contains("Ignore UI chrome"))
        assertTrue(input.contains("Preserve the original point of view (1st/2nd/3rd person) and tense."))
        assertTrue(input.endsWith("Return only the response text, ready to send."))
    }

    @Test
    fun `short context with focusedInputText emits Continue branch with quoted hint`() {
        val above = "Subject: lunch\nHi Dana, can we move it to 1pm?"
        val draft = "Sure, that works"

        val (input, _) = ReadRespondPromptBuilder.build(
            aboveInputText = above,
            focusedInputText = draft,
            persona = persona,
        )

        assertTrue(input.contains(above))
        assertTrue(
            "expected quoted draft in input, got: $input",
            input.contains("I've started typing my response: \"Sure, that works\""),
        )
        assertTrue(input.contains("Continue or rewrite my response in the Tester voice."))
        assertFalse(input.contains("Respond to the latest message above in the"))
        assertTrue(input.contains("Ignore UI chrome"))
        assertTrue(input.contains("Preserve the original point of view (1st/2nd/3rd person) and tense."))
    }

    @Test
    fun `aboveInputText longer than MAX with newline in window resumes after newline`() {
        // Build text that overflows by exactly 50 chars and has a newline 50 chars
        // into the truncation window (i.e. at position len - MAX + 50). The
        // truncated output must begin AFTER that newline — never include the
        // partial-line head before it.
        val max = ReadRespondPromptBuilder.MAX_CONTEXT_CHARS
        val overflow = 200
        val tailMarker = "TAIL_BOUNDARY"
        val head = "X".repeat(overflow + 50)
        val mid = head + "\n" + tailMarker + "Y".repeat(max - 50 - tailMarker.length - 1)
        // Total length = (overflow + 50) + 1 + (max - 50 - 1) = max + overflow.
        assertEquals(max + overflow, mid.length)

        val (input, _) = ReadRespondPromptBuilder.build(
            aboveInputText = mid,
            focusedInputText = "",
            persona = persona,
        )

        // The truncated context block sits between the two "---" delimiters.
        val openIdx = input.indexOf("---\n") + "---\n".length
        val closeIdx = input.indexOf("\n---\n\n")
        assertTrue("expected delimiters in $input", openIdx > 0 && closeIdx > openIdx)
        val context = input.substring(openIdx, closeIdx)

        assertTrue(
            "expected truncated context to start with the post-newline TAIL marker, got: ${context.take(40)}",
            context.startsWith(tailMarker),
        )
        // None of the head 'X' run should survive the truncation.
        assertFalse(context.contains("XXX"))
    }

    @Test
    fun `aboveInputText longer than MAX with no newline keeps last MAX chars verbatim`() {
        val max = ReadRespondPromptBuilder.MAX_CONTEXT_CHARS
        // No newlines at all — truncation falls back to the raw last MAX chars.
        val src = "A".repeat(100) + "B".repeat(max)
        assertEquals(100 + max, src.length)

        val (input, _) = ReadRespondPromptBuilder.build(
            aboveInputText = src,
            focusedInputText = "",
            persona = persona,
        )

        val openIdx = input.indexOf("---\n") + "---\n".length
        val closeIdx = input.indexOf("\n---\n\n")
        val context = input.substring(openIdx, closeIdx)

        assertEquals(max, context.length)
        assertEquals("B".repeat(max), context)
        assertFalse(context.contains("A"))
    }

    @Test
    fun `systemPrompt is passed through verbatim from the persona`() {
        val customPersona = Persona(
            id = "weird",
            name = "Weird",
            systemPrompt = "Speak only in haiku.\nThree lines, syllable counts: 5/7/5.",
        )

        val (_, systemPrompt) = ReadRespondPromptBuilder.build(
            aboveInputText = "doesn't matter",
            focusedInputText = "",
            persona = customPersona,
        )

        assertEquals(
            "Speak only in haiku.\nThree lines, syllable counts: 5/7/5.",
            systemPrompt,
        )
    }
}
