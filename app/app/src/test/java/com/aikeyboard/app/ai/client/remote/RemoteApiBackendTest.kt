// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.remote

import com.aikeyboard.app.ai.persona.FewShot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteApiBackendTest {

    @Test
    fun anthropicRequest_hasExpectedShape() {
        val req = RemoteApiBackend.buildAnthropicRequest(
            input = "Rewrite this concisely.",
            systemPrompt = "You are an editor.",
            fewShots = emptyList(),
            apiKey = "sk-ant-test",
            model = "claude-sonnet-4-6",
        )

        assertEquals("https://api.anthropic.com/v1/messages", req.url)
        assertEquals("sk-ant-test", req.headers["x-api-key"])
        assertEquals("2023-06-01", req.headers["anthropic-version"])
        assertEquals("application/json", req.headers["content-type"])
        assertEquals("text/event-stream", req.headers["accept"])

        val body = Json.parseToJsonElement(req.jsonBody).jsonObject
        assertEquals("claude-sonnet-4-6", body["model"]!!.jsonPrimitive.content)
        assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)
        assertEquals(4096, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("You are an editor.", body["system"]!!.jsonPrimitive.content)

        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        val userMsg = messages[0].jsonObject
        assertEquals("user", userMsg["role"]!!.jsonPrimitive.content)
        assertEquals("Rewrite this concisely.", userMsg["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicRequest_omitsEmptySystemPrompt() {
        val req = RemoteApiBackend.buildAnthropicRequest(
            input = "hi",
            systemPrompt = "",
            fewShots = emptyList(),
            apiKey = "k",
            model = "claude-sonnet-4-6",
        )
        val body = Json.parseToJsonElement(req.jsonBody).jsonObject
        assertNull(body["system"])
    }

    @Test
    fun anthropicRequest_inlinesFewShotsBeforeUserTurn() {
        val req = RemoteApiBackend.buildAnthropicRequest(
            input = "third user turn",
            systemPrompt = "sys",
            fewShots = listOf(FewShot("u1", "a1"), FewShot("u2", "a2")),
            apiKey = "k",
            model = "claude-sonnet-4-6",
        )
        val msgs = Json.parseToJsonElement(req.jsonBody).jsonObject["messages"]!!.jsonArray
        assertEquals(5, msgs.size)
        assertEquals("user", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("u1", msgs[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("assistant", msgs[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("a1", msgs[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", msgs[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("u2", msgs[2].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("assistant", msgs[3].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("a2", msgs[3].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", msgs[4].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("third user turn", msgs[4].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun geminiRequest_hasExpectedShape() {
        val req = RemoteApiBackend.buildGeminiRequest(
            input = "Rewrite this concisely.",
            systemPrompt = "You are an editor.",
            fewShots = emptyList(),
            apiKey = "AIza-test",
            model = "gemini-2.5-flash",
        )

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "gemini-2.5-flash:streamGenerateContent?alt=sse&key=AIza-test",
            req.url,
        )
        assertEquals("application/json", req.headers["content-type"])
        assertEquals("text/event-stream", req.headers["accept"])
        // Gemini puts the key in the query string; assert no auth header carries it as well
        // (would otherwise leak in a header dump).
        assertNull(req.headers["x-api-key"])
        assertNull(req.headers["authorization"])

        val body = Json.parseToJsonElement(req.jsonBody).jsonObject
        val sysParts = body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray
        assertEquals("You are an editor.", sysParts[0].jsonObject["text"]!!.jsonPrimitive.content)

        val contents = body["contents"]!!.jsonArray
        assertEquals(1, contents.size)
        val turn = contents[0].jsonObject
        assertEquals("user", turn["role"]!!.jsonPrimitive.content)
        val parts = turn["parts"]!!.jsonArray
        assertEquals("Rewrite this concisely.", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun geminiRequest_omitsSystemInstructionWhenEmpty() {
        val req = RemoteApiBackend.buildGeminiRequest(
            input = "hi",
            systemPrompt = "",
            fewShots = emptyList(),
            apiKey = "k",
            model = "gemini-2.5-flash",
        )
        val body = Json.parseToJsonElement(req.jsonBody).jsonObject
        assertNull(body["systemInstruction"])
    }

    @Test
    fun geminiRequest_modelRoleForAssistantFewShots() {
        // Gemini uses {role:"model"} for the assistant turn, not "assistant"; verify we map.
        val req = RemoteApiBackend.buildGeminiRequest(
            input = "third turn",
            systemPrompt = "",
            fewShots = listOf(FewShot("u1", "a1")),
            apiKey = "k",
            model = "gemini-2.5-flash",
        )
        val contents = Json.parseToJsonElement(req.jsonBody).jsonObject["contents"]!!.jsonArray
        assertEquals(3, contents.size)
        assertEquals("user", contents[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("model", contents[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", contents[2].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicRequest_handlesQuotesAndNewlines() {
        // Sanity check that JSON escaping works through the buildJsonObject path.
        val tricky = "She said \"hi\"\nand left."
        val req = RemoteApiBackend.buildAnthropicRequest(
            input = tricky,
            systemPrompt = "",
            fewShots = emptyList(),
            apiKey = "k",
            model = "claude-sonnet-4-6",
        )
        val msgs = Json.parseToJsonElement(req.jsonBody).jsonObject["messages"]!!.jsonArray
        assertEquals(tricky, msgs[0].jsonObject["content"]!!.jsonPrimitive.contentOrNull)
        // The serialized body must escape both characters.
        assertTrue(req.jsonBody.contains("\\\""))
        assertTrue(req.jsonBody.contains("\\n"))
    }

}
