// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui.health

import android.content.Intent

/**
 * Phase 12 §10: structural model for the health diagnostics screen.
 *
 * Privacy: a HealthDiagnostic is intentionally NOT carrying user data.
 * `label` and `detail` resolve to localized resource strings or structural
 * identifiers (backend enum name, provider id, integer counts) — never API
 * keys, URLs with query params, prompt text, or response bodies. The "Copy
 * report" path serializes these instances verbatim into clipboard text; if
 * `detail` ever held user data, the copy would leak it.
 */
internal data class HealthDiagnostic(
    val label: String,
    val status: HealthStatus,
    val detail: String? = null,
    val recoveryIntent: Intent? = null,
)

internal enum class HealthStatus {
    /** Passing check — user action not required. */
    OK,

    /** Non-blocking warning — e.g. the feature is optional and currently off. */
    WARN,

    /** Failing check — surfaces the recovery intent if one exists. */
    FAIL,

    /** Feature is unsupported in this build but the row is still informative.
     *  Used for the a11y row on the play flavor: hidden entirely. */
    NEUTRAL,

    /** Row is structurally absent from the report (e.g. bridge providers row
     *  when the user isn't on the Termux backend). Rendered as nothing; the
     *  format pass omits it from the clipboard string entirely. */
    HIDDEN,
}
