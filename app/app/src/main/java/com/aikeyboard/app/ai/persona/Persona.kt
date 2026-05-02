// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.persona

import kotlinx.serialization.Serializable

@Serializable
data class Persona(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val fewShots: List<FewShot> = emptyList(),
    val isBuiltIn: Boolean = false
)

@Serializable
data class FewShot(
    val userInput: String,
    val assistantResponse: String
)
