// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.commandrow

import com.aikeyboard.app.ai.persona.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of the Rewrite task-framing template. Symmetric to
 * ReadRespondPromptBuilderTest. Pins the literal scaffold so a future regression
 * doesn't silently change what gets sent on the rewrite path.
 */
class RewritePromptBuilderTest {

    private val persona = Persona(
        id = "p-test",
        name = "Tester",
        systemPrompt = "You are a helpful tester.",
    )

    @Test
    fun `single line input is wrapped with framing and persona name`() {
        val built = RewritePromptBuilder.build("hello world", persona)

        assertTrue(
            "expected framing prefix, got: $built",
            built.startsWith("Rewrite the following text in the Tester voice, "),
        )
        assertTrue(built.contains("preserving the original point of view (1st/2nd/3rd person) and tense."))
        assertTrue(built.contains("Return only the rewritten text."))
        assertTrue(
            "expected user input at end after blank line, got: $built",
            built.endsWith("\n\nhello world"),
        )
    }

    @Test
    fun `multi-line input is preserved verbatim after the framing`() {
        val input = "First line.\nSecond line.\n  indented third"
        val built = RewritePromptBuilder.build(input, persona)

        assertTrue(built.endsWith("\n\n" + input))
        // No accidental leading-whitespace stripping or line-ending normalization.
        assertTrue(built.contains("\n  indented third"))
    }

    @Test
    fun `persona name interpolates into the voice clause`() {
        val flirty = persona.copy(name = "Flirty")
        val built = RewritePromptBuilder.build("anything", flirty)
        assertTrue(built.contains("in the Flirty voice,"))
    }

    @Test
    fun `empty persona system prompt is not consulted by the builder`() {
        val noSystem = persona.copy(systemPrompt = "")
        val built = RewritePromptBuilder.build("input text", noSystem)
        // Builder operates on persona.name + input only; systemPrompt is the
        // caller's responsibility to pass to backend.rewrite separately.
        assertEquals(
            "Rewrite the following text in the Tester voice, " +
                "preserving the original point of view (1st/2nd/3rd person) and tense. " +
                "Return only the rewritten text.\n\ninput text",
            built,
        )
    }
}
