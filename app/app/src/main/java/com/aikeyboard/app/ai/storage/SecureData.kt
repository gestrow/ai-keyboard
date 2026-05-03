// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.storage

import com.aikeyboard.app.ai.persona.Persona
import kotlinx.serialization.Serializable

/**
 * Plain envelope serialized as JSON, then sealed by Tink AEAD into the on-disk
 * blob managed by {@link SecureStorage}. Internal so the JVM unit tests can
 * exercise the envelope round-trip without depending on Android Keystore.
 */
@Serializable
internal data class SecureData(
    val personas: List<Persona> = emptyList(),
    val activePersonaId: String? = null,
    val apiKeys: Map<String, String> = emptyMap(),
)
