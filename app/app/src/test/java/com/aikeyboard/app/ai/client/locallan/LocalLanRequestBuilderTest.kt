// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.locallan

import com.aikeyboard.app.ai.persona.FewShot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalLanRequestBuilderTest {

    @Test fun buildMessages_systemPromptFirst() {
        val messages = LocalLanRequestBuilder.buildMessages(
            input = "do the thing",
            systemPrompt = "be terse",
            fewShots = emptyList(),
        )
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].role)
        assertEquals("be terse", messages[0].content)
        assertEquals("user", messages[1].role)
        assertEquals("do the thing", messages[1].content)
    }

    @Test fun buildMessages_skipsBlankSystemPrompt() {
        val messages = LocalLanRequestBuilder.buildMessages(
            input = "hello",
            systemPrompt = "   ",
            fewShots = emptyList(),
        )
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].role)
    }

    @Test fun buildMessages_fewShotsBeforeUser() {
        val messages = LocalLanRequestBuilder.buildMessages(
            input = "rewrite this",
            systemPrompt = "act professional",
            fewShots = listOf(
                FewShot("hi there", "Hi — how can I help?"),
                FewShot("bye", "Goodbye."),
            ),
        )
        assertEquals(6, messages.size)
        assertEquals("system", messages[0].role)
        assertEquals("user", messages[1].role); assertEquals("hi there", messages[1].content)
        assertEquals("assistant", messages[2].role); assertEquals("Hi — how can I help?", messages[2].content)
        assertEquals("user", messages[3].role); assertEquals("bye", messages[3].content)
        assertEquals("assistant", messages[4].role); assertEquals("Goodbye.", messages[4].content)
        assertEquals("user", messages[5].role); assertEquals("rewrite this", messages[5].content)
    }

    @Test fun ollamaBody_serializesShape() {
        val body = LocalLanRequestBuilder.ollamaBody(
            model = "llama3.2",
            messages = listOf(ChatMessage("system", "x"), ChatMessage("user", "y")),
        )
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals("llama3.2", obj["model"]!!.jsonPrimitive.content)
        assertEquals(true, obj["stream"]!!.jsonPrimitive.boolean)
        val arr = obj["messages"]!!.jsonArray
        assertEquals(2, arr.size)
        assertEquals("system", arr[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", arr[1].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test fun openAiBody_serializesShape() {
        val body = LocalLanRequestBuilder.openAiBody(
            model = "gpt-3.5-turbo",
            messages = listOf(ChatMessage("user", "hi")),
        )
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals("gpt-3.5-turbo", obj["model"]!!.jsonPrimitive.content)
        assertEquals(true, obj["stream"]!!.jsonPrimitive.boolean)
        assertTrue(obj["messages"]!!.jsonArray.size == 1)
    }
}
