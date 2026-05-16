// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.a11y

import com.aikeyboard.app.ai.persona.Persona

/**
 * Pure prompt builder for Read & Respond. Takes a ScreenContext snapshot and
 * the user's typed-but-uncommitted hint, returns the (input, systemPrompt)
 * pair that gets fed to `AiClient.rewrite(...)`.
 *
 * Truncation: aboveInputText is truncated to the LAST `MAX_CONTEXT_CHARS`
 * characters before being inserted into the prompt. "Last" because in chat
 * UIs the most recent message is at the bottom of the visible area and is the
 * most relevant context for "respond to this." Truncation preserves message
 * boundaries when convenient (drop from the start of the truncated window
 * back to the next newline) but never re-orders content.
 *
 * Privacy: this object does no logging at all. The input strings flow
 * straight into the returned pair; the call site is responsible for keeping
 * those values out of logs.
 */
object ReadRespondPromptBuilder {

    const val MAX_CONTEXT_CHARS = 4_000

    /**
     * @param aboveInputText the screen content above the focused input (the
     *   "what we're responding to" context); must not be blank — caller must
     *   short-circuit with a toast before calling here if it is.
     * @param focusedInputText the user's current uncommitted text in the
     *   underlying app's input field, or empty if they haven't typed yet.
     * @param persona the active persona (system prompt + voice).
     * @return Pair(input, systemPrompt) for AiClient.rewrite. fewShots stays
     *   empty — Read & Respond is a one-shot generation, not a rewrite.
     */
    fun build(
        aboveInputText: String,
        focusedInputText: String,
        persona: Persona,
    ): Pair<String, String> {
        val context = truncateFromStart(aboveInputText, MAX_CONTEXT_CHARS)

        val input = buildString {
            append("I'm reading this on my screen:\n---\n")
            append(context)
            append("\n---\n\n")
            if (focusedInputText.isNotBlank()) {
                append("I've started typing my response: \"")
                append(focusedInputText)
                append("\"\n\n")
                append("Continue or rewrite my response in the ")
                append(persona.name)
                append(" voice. ")
            } else {
                append("Respond to the latest message above in the ")
                append(persona.name)
                append(" voice. ")
            }
            append("Ignore UI chrome like empty text fields, button labels, hint text, and timestamps — only respond to conversational content. ")
            append("Preserve the original point of view (1st/2nd/3rd person) and tense. ")
            append("Don't repeat what's already visible above. Return only the response text, ready to send.")
        }

        return input to persona.systemPrompt
    }

    /**
     * Keeps the LAST N chars (most recent screen content). If we truncate
     * mid-line, advance to the next `\n` to avoid a fragmented opening.
     */
    private fun truncateFromStart(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val tailStart = text.length - maxChars
        val newlineAfter = text.indexOf('\n', tailStart)
        return if (newlineAfter == -1 || newlineAfter == text.length - 1) {
            text.substring(tailStart)
        } else {
            text.substring(newlineAfter + 1)
        }
    }
}
