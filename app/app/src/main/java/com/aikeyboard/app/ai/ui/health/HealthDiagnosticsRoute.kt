// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui.health

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.aikeyboard.app.latin.BuildConfig

/**
 * Phase 12 §10: state hoist for the health diagnostics screen.
 *
 * Holds the loading-state + diagnostic list, fires the checker via
 * LaunchedEffect on entry, and wires the copy-to-clipboard action to
 * `HealthReportFormat`. Compose-pure: no Activity / Fragment lifecycle
 * coupling beyond what the NavHost composable already provides.
 */
@Composable
internal fun HealthDiagnosticsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var diagnostics by remember { mutableStateOf<List<HealthDiagnostic>>(emptyList()) }

    LaunchedEffect(Unit) {
        val checker = HealthDiagnosticChecker(context.applicationContext)
        diagnostics = checker.runAll()
        isLoading = false
    }

    HealthDiagnosticsScreen(
        isLoading = isLoading,
        diagnostics = diagnostics,
        onBack = onBack,
        onCopy = { copyReportToClipboard(context, diagnostics) },
    )
}

private fun copyReportToClipboard(context: Context, diagnostics: List<HealthDiagnostic>) {
    val report = HealthReportFormat.format(
        diagnostics = diagnostics,
        versionName = BuildConfig.VERSION_NAME,
        flavor = "${BuildConfig.FLAVOR} ${BuildConfig.BUILD_TYPE}",
    )
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Health Diagnostic", report))
}
