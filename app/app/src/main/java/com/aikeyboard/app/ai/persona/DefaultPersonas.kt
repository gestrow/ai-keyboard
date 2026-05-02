// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.persona

object DefaultPersonas {
    const val DEFAULT_ID = "00000000-0000-0000-0000-000000000001"
    const val CONCISE_EDITOR_ID = "00000000-0000-0000-0000-000000000002"
    const val ESQUIRE_ID = "00000000-0000-0000-0000-000000000003"
    const val FLIRTY_ID = "00000000-0000-0000-0000-000000000004"

    val all: List<Persona> = listOf(
        Persona(
            id = DEFAULT_ID,
            name = "Default",
            systemPrompt = "",
            isBuiltIn = true
        ),
        Persona(
            id = CONCISE_EDITOR_ID,
            name = "Concise Editor",
            systemPrompt = "Rewrite the user's text to be more concise while preserving meaning. " +
                "Output only the rewritten text. No preamble, no explanation.",
            isBuiltIn = true
        ),
        Persona(
            id = ESQUIRE_ID,
            name = "Esquire",
            systemPrompt = "Rewrite the user's text in formal legal/professional tone, " +
                "suitable for a written communication. Output only the rewritten text. No preamble.",
            isBuiltIn = true
        ),
        Persona(
            id = FLIRTY_ID,
            name = "Flirty",
            systemPrompt = "Rewrite the user's text with a playful, lightly flirtatious tone. " +
                "Stay tasteful. Output only the rewritten text. No preamble.",
            isBuiltIn = true
        )
    )
}
