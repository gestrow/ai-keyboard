// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui.health

import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 12 §10.4 pure-JVM tests for the clipboard report. Avoids Robolectric
 * (broken on SDK 36 in this project — see Phase 6+ carry-over).
 */
class HealthReportFormatTest {

    @Test
    fun emptyDiagnosticList_formatsHeaderOnly() {
        val report = HealthReportFormat.format(
            diagnostics = emptyList(),
            versionName = "0.1.0",
            flavor = "fdroid debug",
            timestamp = Date(0L),
        )
        assertTrue(report.startsWith("AI Keyboard Health Diagnostic — "))
        assertTrue(report.contains("Version: 0.1.0 (fdroid debug)"))
        // No trailing body when there are no visible rows.
        assertFalse(report.contains("[OK]"))
        assertFalse(report.contains("[FAIL]"))
    }

    @Test
    fun hiddenRows_omittedFromReport() {
        val report = HealthReportFormat.format(
            diagnostics = listOf(
                HealthDiagnostic("Visible row", HealthStatus.OK),
                HealthDiagnostic("Hidden row", HealthStatus.HIDDEN, "should not appear"),
            ),
            versionName = "0.1.0",
            flavor = "fdroid debug",
            timestamp = Date(0L),
        )
        assertTrue(report.contains("[OK] Visible row"))
        assertFalse(report.contains("Hidden row"))
        assertFalse(report.contains("should not appear"))
    }

    @Test
    fun reportContainsNoAtSigns_cheapPrivacyHeuristic() {
        // The '@' character is in both email addresses and many API keys
        // (e.g. "user@example.com", or @-prefixed package names). Asserting
        // its absence is a cheap drift guard against accidental leaks via
        // the detail field. Bump this test deliberately when intentionally
        // adding an at-sign-bearing legitimate string.
        val report = HealthReportFormat.format(
            diagnostics = listOf(
                HealthDiagnostic("Backend configured", HealthStatus.OK, "REMOTE_API (Anthropic Claude)"),
                HealthDiagnostic("IME enabled", HealthStatus.OK),
                HealthDiagnostic("Bridge providers", HealthStatus.WARN, "claude:ok, gemini:off"),
            ),
            versionName = "0.1.0",
            flavor = "fdroid release",
            timestamp = Date(0L),
        )
        assertFalse('@' in report, "Report contains '@' character — possible leak: $report")
    }

    @Test
    fun statusEnumNamesRoundTripIntoReport() {
        val report = HealthReportFormat.format(
            diagnostics = listOf(
                HealthDiagnostic("ok-row", HealthStatus.OK),
                HealthDiagnostic("warn-row", HealthStatus.WARN),
                HealthDiagnostic("fail-row", HealthStatus.FAIL),
                HealthDiagnostic("neutral-row", HealthStatus.NEUTRAL),
            ),
            versionName = "0.1.0",
            flavor = "fdroid debug",
            timestamp = Date(0L),
        )
        // Each non-HIDDEN status surfaces as a bracketed tag.
        assertTrue("[OK] ok-row" in report)
        assertTrue("[WARN] warn-row" in report)
        assertTrue("[FAIL] fail-row" in report)
        assertTrue("[NEUTRAL] neutral-row" in report)
    }

    @Test
    fun timestampIsStableForFixedDate() {
        // Fixed epoch instant → fixed report header (modulo TZ; the format
        // uses Locale.US + default TZ, so the test runs in whatever the CI
        // machine's TZ is; we just verify the header shape, not the literal).
        val report = HealthReportFormat.format(
            diagnostics = emptyList(),
            versionName = "0.1.0",
            flavor = "fdroid release",
            timestamp = Date(0L),
        )
        // Format: "AI Keyboard Health Diagnostic — yyyy-MM-dd HH:mm:ss"
        val header = report.lineSequence().first()
        assertEquals("AI Keyboard Health Diagnostic — ", header.substring(0, "AI Keyboard Health Diagnostic — ".length))
        // 19 chars for the timestamp.
        val tsPart = header.substring("AI Keyboard Health Diagnostic — ".length)
        assertEquals(19, tsPart.length, "Expected 19-char timestamp, got '$tsPart'")
    }
}
