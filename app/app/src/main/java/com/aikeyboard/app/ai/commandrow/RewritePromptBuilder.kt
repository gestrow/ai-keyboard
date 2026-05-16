// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.commandrow

import com.aikeyboard.app.ai.persona.Persona

/**
 * Wraps the user's selected/full-field text in a one-line task framing that
 * tells the model to (a) preserve point of view and tense, (b) return only
 * the rewritten text. The persona's system prompt provides the voice; this
 * builder's job is the task framing, not the persona instructions.
 *
 * Symmetric counterpart to [com.aikeyboard.app.ai.a11y.ReadRespondPromptBuilder]
 * for the rewrite path. Pure-JVM; no Android dependencies — easy to snapshot-test.
 */
object RewritePromptBuilder {
    fun build(input: String, persona: Persona): String = buildString {
        append("Rewrite the following text in the ")
        append(persona.name)
        append(" voice, preserving the original point of view (1st/2nd/3rd person) and tense. ")
        append("Return only the rewritten text.\n\n")
        append(input)
    }
}
