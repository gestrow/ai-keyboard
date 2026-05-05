// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.storage

import com.aikeyboard.app.ai.client.Provider
import com.aikeyboard.app.ai.persona.FewShot
import com.aikeyboard.app.ai.persona.Persona
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the SecureStorage envelope and the API-key CRUD shape.
 * The Tink AEAD path itself is verified by the on-device migration smoke test;
 * Android Keystore doesn't initialize cleanly in Robolectric.
 */
class SecureStorageTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun envelope_roundTrip_preservesAllFields() {
        val original = SecureData(
            personas = listOf(
                Persona(id = "1", name = "Default", systemPrompt = "", isBuiltIn = true),
                Persona(
                    id = "2",
                    name = "Custom",
                    systemPrompt = "Be terse.",
                    fewShots = listOf(FewShot("hello", "hi")),
                ),
            ),
            activePersonaId = "2",
            apiKeys = mapOf("anthropic" to "sk-test", "google_gemini" to "AIza-test"),
        )

        val encoded = json.encodeToString(SecureData.serializer(), original)
        val decoded = json.decodeFromString(SecureData.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun envelope_default_isEmpty() {
        val empty = SecureData()
        val encoded = json.encodeToString(SecureData.serializer(), empty)
        val decoded = json.decodeFromString(SecureData.serializer(), encoded)
        assertEquals(emptyList(), decoded.personas)
        assertNull(decoded.activePersonaId)
        assertTrue(decoded.apiKeys.isEmpty())
    }

    @Test
    fun envelope_apiKeyMutations_areIdempotent() {
        val data = SecureData(apiKeys = mapOf("anthropic" to "k1"))

        val withGemini = data.copy(
            apiKeys = data.apiKeys.toMutableMap().apply { put("google_gemini", "k2") },
        )
        assertEquals(setOf("anthropic", "google_gemini"), withGemini.apiKeys.keys)
        assertEquals("k1", withGemini.apiKeys["anthropic"])
        assertEquals("k2", withGemini.apiKeys["google_gemini"])

        val withoutAnthropic = withGemini.copy(
            apiKeys = withGemini.apiKeys.toMutableMap().apply { remove("anthropic") },
        )
        assertEquals(setOf("google_gemini"), withoutAnthropic.apiKeys.keys)
    }

    @Test
    fun providerEnum_storageKeysAreStable() {
        // Storage keys are persisted; renaming them silently would orphan user API keys.
        assertEquals("anthropic", Provider.ANTHROPIC.storageKey)
        assertEquals("google_gemini", Provider.GOOGLE_GEMINI.storageKey)
        assertEquals(Provider.ANTHROPIC, Provider.fromStorageKey("anthropic"))
        assertEquals(Provider.GOOGLE_GEMINI, Provider.fromStorageKey("google_gemini"))
        assertNull(Provider.fromStorageKey("unknown"))
    }

    @Test
    fun envelope_alwaysOnEnabled_defaultsFalse_andRoundTrips() {
        val empty = SecureData()
        assertEquals(false, empty.alwaysOnEnabled)

        val toggled = empty.copy(alwaysOnEnabled = true)
        val encoded = json.encodeToString(SecureData.serializer(), toggled)
        val decoded = json.decodeFromString(SecureData.serializer(), encoded)
        assertEquals(true, decoded.alwaysOnEnabled)
    }
}
