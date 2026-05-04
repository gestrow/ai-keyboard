// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.a11y

/**
 * Snapshot of a single window-tree walk, captured on demand by the
 * AccessibilityService. Phase 7b's "Read & Respond" button will package this
 * (plus the user's input text) into the prompt sent to the active backend.
 *
 * Privacy: instances of this type carry user-visible screen text; never log
 * `nodes`, `focusedInputText`, or `aboveInputText` content. Diagnostic logs
 * may use only `nodeCount` and `walkDurationMs`.
 *
 * Lives in the `main` source set (not `fdroid`) so Phase 7b's wiring code,
 * which is guarded by `BuildConfig.ENABLE_A11Y` but compiled into both
 * flavors, can reference the type without flavor-conditional imports. The
 * play flavor compiles the type but never produces an instance — there is no
 * service to invoke.
 */
data class ScreenContext(
    /** All text-bearing nodes in document order, top-to-bottom. */
    val nodes: List<TextNode>,
    /** Index into `nodes` of the currently-focused input field, or -1 if none. */
    val focusedInputIndex: Int,
    /** Convenience: text from `nodes` BEFORE the focused input (the "above" hint). */
    val aboveInputText: String,
    /** Convenience: text inside the focused input field (may be empty). */
    val focusedInputText: String,
    val nodeCount: Int,
    val walkDurationMs: Long,
)

data class TextNode(
    val text: String,            // node.text ?: node.contentDescription ?: ""
    val isInputField: Boolean,   // node.isEditable
    val isFocused: Boolean,      // node.isAccessibilityFocused || node.isFocused
    val className: String,       // for debugging only — never logged
)

sealed interface ScreenReaderResult {
    data class Success(val context: ScreenContext) : ScreenReaderResult

    /**
     * Typed failure modes. Phase 7b's wiring will map each value to a
     * localized toast / inline error; structural enough that the wiring can
     * `when (failure)` exhaustively over the enum.
     */
    enum class Failure : ScreenReaderResult {
        SERVICE_NOT_ENABLED,    // user hasn't granted a11y access (instance == null)
        NO_ACTIVE_WINDOW,       // getRootInActiveWindow returned null
        BUILD_DOES_NOT_SUPPORT, // play flavor — caller should never invoke, but defensive
        UNKNOWN_FAILURE,        // unexpected exception during walk
    }
}
