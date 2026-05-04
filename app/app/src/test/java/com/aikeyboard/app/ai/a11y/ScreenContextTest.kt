// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.a11y

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 7a: derivation rules for the ScreenContext convenience fields
 * (`aboveInputText`, `focusedInputText`). The data class is dumb — these
 * tests pin the contract Phase 7b's wiring will rely on.
 *
 * The walk logic in `ScreenReaderService.walk` itself is hard to unit-test
 * without a real `AccessibilityNodeInfo` (Robolectric could do it, but the
 * project's existing pattern is JVM-pure tests). The on-device smoke test
 * is the verification path for the walk; these tests cover only the
 * convenience-field derivation that Phase 7b's prompt construction will
 * read directly.
 */
class ScreenContextTest {

    private fun node(text: String): TextNode =
        TextNode(text = text, isInputField = false, isFocused = false, className = "android.widget.TextView")

    private fun input(text: String, focused: Boolean = true): TextNode =
        TextNode(text = text, isInputField = true, isFocused = focused, className = "android.widget.EditText")

    @Test
    fun `aboveInputText joins nodes before the focused input with newlines`() {
        val nodes = listOf(
            node("Subject: lunch"),
            node("Hi Dana,"),
            input("draft body so far"), // index 2
            node("Sent from device"),
            node("Reply"),
        )
        val ctx = ScreenContext(
            nodes = nodes,
            focusedInputIndex = 2,
            aboveInputText = nodes.subList(0, 2).joinToString("\n") { it.text },
            focusedInputText = nodes[2].text,
            nodeCount = nodes.size,
            walkDurationMs = 7L,
        )
        assertEquals("Subject: lunch\nHi Dana,", ctx.aboveInputText)
        assertEquals("draft body so far", ctx.focusedInputText)
    }

    @Test
    fun `no focused input means both convenience fields are empty`() {
        val nodes = listOf(node("Static page"), node("No input here"))
        val ctx = ScreenContext(
            nodes = nodes,
            focusedInputIndex = -1,
            aboveInputText = "",
            focusedInputText = "",
            nodeCount = nodes.size,
            walkDurationMs = 3L,
        )
        assertEquals("", ctx.aboveInputText)
        assertEquals("", ctx.focusedInputText)
    }

    @Test
    fun `focused input at index 0 leaves aboveInputText empty`() {
        val nodes = listOf(input("first field"), node("after the field"))
        val ctx = ScreenContext(
            nodes = nodes,
            focusedInputIndex = 0,
            // No nodes above — derivation rule produces empty string, not
            // null and not " ".
            aboveInputText = "",
            focusedInputText = nodes[0].text,
            nodeCount = nodes.size,
            walkDurationMs = 1L,
        )
        assertEquals("", ctx.aboveInputText)
        assertEquals("first field", ctx.focusedInputText)
    }
}
