// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui.health

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 12 §10.3: serializes a list of HealthDiagnostic rows into a
 * plain-text report suitable for clipboard copy. HIDDEN rows are omitted.
 *
 * Privacy: only structural labels + status codes + sanitized detail strings
 * make it into the report. The `HealthReportFormatTest::reportContainsNoAtSigns`
 * cheap heuristic catches accidental email / API-key leaks ('@' is in both).
 */
internal object HealthReportFormat {

    fun format(
        diagnostics: List<HealthDiagnostic>,
        versionName: String,
        flavor: String,
        timestamp: Date = Date(),
    ): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(timestamp)
        val header = buildString {
            appendLine("AI Keyboard Health Diagnostic — $ts")
            appendLine("Version: $versionName ($flavor)")
        }
        val visible = diagnostics.filter { it.status != HealthStatus.HIDDEN }
        if (visible.isEmpty()) return header.trimEnd()
        val body = visible.joinToString("\n") { d ->
            val statusTag = "[${d.status.name}]"
            val tail = d.detail?.let { ": $it" } ?: ""
            "$statusTag ${d.label}$tail"
        }
        return "$header\n$body"
    }
}
