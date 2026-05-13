// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.remote

import com.aikeyboard.app.ai.persona.FewShot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 11 — xAI Grok request shape. Pure-JVM; mirrors the
 * RemoteApiBackendTest pattern for Anthropic / Gemini.
 *
 * Grok speaks the OpenAI-compatible /v1/chat/completions wire format, so the
 * body shape is checked against `LocalLanRequestBuilder.openAiBody`'s output.
 */
class GrokRequestTest {

    @Test
    fun grokRequest_targetsApiXAiChatCompletions() {
        val req = RemoteApiBackend.buildGrokRequest(
            input = "Rewrite this.",
            systemPrompt = "Be terse.",
            fewShots = emptyList(),
            apiKey = "xai-key",
            model = "grok-2-latest",
        )
        assertEquals("https://api.x.ai/v1/chat/completions", req.url)
    }

    @Test
    fun grokRequest_setsBearerAuthHeader() {
        val req = RemoteApiBackend.buildGrokRequest(
            input = "hi",
            systemPrompt = "",
            fewShots = emptyList(),
            apiKey = "xai-secret",
            model = "grok-2-latest",
        )
        assertEquals("Bearer xai-secret", req.headers["authorization"])
        assertEquals("application/json", req.headers["content-type"])
        assertEquals("text/event-stream", req.headers["accept"])
        // Defense-in-depth: the Anthropic-style x-api-key header must NOT
        // carry a duplicate of the secret (would otherwise leak via header dump).
        assertNull(req.headers["x-api-key"])
    }

    @Test
    fun grokRequest_bodyIsOpenAiChatCompletionsShape() {
        val req = RemoteApiBackend.buildGrokRequest(
            input = "Rewrite this concisely.",
            systemPrompt = "You are an editor.",
            fewShots = emptyList(),
            apiKey = "k",
            model = "grok-2-latest",
        )
        val body = Json.parseToJsonElement(req.jsonBody).jsonObject
        assertEquals("grok-2-latest", body["model"]!!.jsonPrimitive.content)
        assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)

        val msgs = body["messages"]!!.jsonArray
        assertEquals(2, msgs.size)
        assertEquals("system", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("You are an editor.", msgs[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", msgs[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Rewrite this concisely.", msgs[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun grokRequest_inlinesFewShotsBetweenSystemAndFinalUser() {
        val req = RemoteApiBackend.buildGrokRequest(
            input = "third user turn",
            systemPrompt = "sys",
            fewShots = listOf(FewShot("u1", "a1"), FewShot("u2", "a2")),
            apiKey = "k",
            model = "grok-2-latest",
        )
        val msgs = Json.parseToJsonElement(req.jsonBody).jsonObject["messages"]!!.jsonArray
        assertEquals(6, msgs.size)
        assertEquals("system", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", msgs[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("u1", msgs[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("assistant", msgs[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("a1", msgs[2].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", msgs[3].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("u2", msgs[3].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("assistant", msgs[4].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("a2", msgs[4].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", msgs[5].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("third user turn", msgs[5].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun grokRequest_omitsSystemRoleWhenSystemPromptBlank() {
        val req = RemoteApiBackend.buildGrokRequest(
            input = "hi",
            systemPrompt = "",
            fewShots = emptyList(),
            apiKey = "k",
            model = "grok-2-latest",
        )
        val msgs = Json.parseToJsonElement(req.jsonBody).jsonObject["messages"]!!.jsonArray
        assertEquals(1, msgs.size)
        assertEquals("user", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
        // System role must not appear when systemPrompt is blank — matches
        // LocalLanRequestBuilder.buildMessages behaviour the helper reuses.
        assertTrue(msgs.none { it.jsonObject["role"]!!.jsonPrimitive.content == "system" })
    }

    @Test
    fun grokRequest_modelDefaultsToGrok2LatestViaProviderEnum() {
        // The Provider.XAI_GROK enum carries the default model name. This test
        // pins that contract so a future enum rename doesn't silently change
        // the model wire string.
        val provider = com.aikeyboard.app.ai.client.Provider.XAI_GROK
        assertEquals("grok-2-latest", provider.defaultModel)
        assertEquals("xai_grok", provider.storageKey)

        val req = RemoteApiBackend.buildGrokRequest(
            input = "hi",
            systemPrompt = "",
            fewShots = emptyList(),
            apiKey = "k",
            model = provider.defaultModel,
        )
        val body = Json.parseToJsonElement(req.jsonBody).jsonObject
        assertEquals("grok-2-latest", body["model"]!!.jsonPrimitive.content)
    }
}
