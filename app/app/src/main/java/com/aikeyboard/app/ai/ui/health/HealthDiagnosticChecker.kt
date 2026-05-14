// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui.health

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.aikeyboard.app.ai.client.BackendStrategy
import com.aikeyboard.app.ai.client.Provider
import com.aikeyboard.app.ai.storage.SecureStorage
import com.aikeyboard.app.ai.termux.TermuxOrchestrator
import com.aikeyboard.app.latin.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 12 §10: runs the structural health checks. Suspends so the bridge
 * /health probe doesn't block the main thread (the IME → loopback probe is
 * usually sub-100ms but is allowed up to TermuxOrchestrator's 2s timeout).
 *
 * The class is intentionally not an `object` and `runAll` is a single
 * call-site `suspend fun`. The "4-for-4 precedent" from prior phases
 * (BackendResolver, ReadRespondPromptBuilder, StickerCommitter,
 * AlwaysOnProxy, LocalLanBackend) shows R8 in release mode will inline
 * single-call-site suspend fun bodies into the surrounding composable
 * lambda, deleting the class from the dex. A proguard keep rule in
 * `proguard-rules.pro` pins this class observable.
 */
internal class HealthDiagnosticChecker(private val context: Context) {

    suspend fun runAll(): List<HealthDiagnostic> = withContext(Dispatchers.IO) {
        listOf(
            checkImeEnabled(),
            checkImeSelected(),
            checkA11yBound(),
            checkBackendConfigured(),
            checkBridgeProviders(),
        )
    }

    private fun checkImeEnabled(): HealthDiagnostic {
        val imm = context.getSystemService(InputMethodManager::class.java)
            ?: return HealthDiagnostic(LABEL_IME_ENABLED, HealthStatus.FAIL, DETAIL_IMM_UNAVAILABLE)
        val ourPkg = context.packageName
        val enabled = imm.enabledInputMethodList?.any { it.packageName == ourPkg } == true
        return if (enabled) HealthDiagnostic(LABEL_IME_ENABLED, HealthStatus.OK)
        else HealthDiagnostic(
            LABEL_IME_ENABLED,
            HealthStatus.FAIL,
            DETAIL_IME_DISABLED,
            recoveryIntent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun checkImeSelected(): HealthDiagnostic {
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        )
        val ourPkg = context.packageName
        // current is a flattened ComponentName like "pkg/ServiceClass". A
        // prefix-match against our package id is sufficient — we don't
        // care which subtype is active.
        val selected = current?.startsWith("$ourPkg/") == true
        return if (selected) HealthDiagnostic(LABEL_IME_SELECTED, HealthStatus.OK)
        else HealthDiagnostic(
            LABEL_IME_SELECTED,
            HealthStatus.WARN,
            DETAIL_IME_NOT_SELECTED,
        )
    }

    private fun checkA11yBound(): HealthDiagnostic {
        if (!BuildConfig.ENABLE_A11Y) {
            return HealthDiagnostic(LABEL_A11Y, HealthStatus.HIDDEN)
        }
        // String-FQN check (no fdroid-only class import in src/main/);
        // identical pattern to AlwaysOnRoute.isAccessibilityServiceEnabled.
        val target = ComponentName(
            context.packageName,
            A11Y_SERVICE_FQN,
        ).flattenToString()
        val setting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        val bound = setting?.split(':')?.any { it == target } == true
        return if (bound) HealthDiagnostic(LABEL_A11Y, HealthStatus.OK)
        else HealthDiagnostic(LABEL_A11Y, HealthStatus.NEUTRAL, DETAIL_A11Y_OFF)
    }

    private fun checkBackendConfigured(): HealthDiagnostic {
        val storage = SecureStorage.getInstance(context)
        return when (val strategy = storage.getSelectedBackendStrategy()) {
            BackendStrategy.REMOTE_API -> {
                val provider: Provider? = storage.getSelectedProvider()
                if (provider == null) {
                    HealthDiagnostic(LABEL_BACKEND, HealthStatus.FAIL, DETAIL_NO_PROVIDER)
                } else {
                    HealthDiagnostic(
                        LABEL_BACKEND,
                        HealthStatus.OK,
                        "REMOTE_API (${provider.displayName})",
                    )
                }
            }
            BackendStrategy.LOCAL_LAN -> {
                val baseUrl = storage.getLocalLanBaseUrl()
                val model = storage.getLocalLanModelName()
                if (baseUrl.isEmpty() || model.isEmpty()) {
                    HealthDiagnostic(LABEL_BACKEND, HealthStatus.FAIL, DETAIL_LOCAL_LAN_INCOMPLETE)
                } else {
                    // PRIVACY: we do not echo the URL — only the structural
                    // strategy name. The user can navigate to BackendsScreen
                    // to inspect their configured URL themselves.
                    HealthDiagnostic(LABEL_BACKEND, HealthStatus.OK, "LOCAL_LAN")
                }
            }
            BackendStrategy.TERMUX_BRIDGE -> {
                val cli = storage.getSelectedTermuxProvider()
                if (cli.isNullOrEmpty()) {
                    HealthDiagnostic(LABEL_BACKEND, HealthStatus.FAIL, DETAIL_TERMUX_NO_CLI)
                } else {
                    HealthDiagnostic(
                        LABEL_BACKEND,
                        HealthStatus.OK,
                        "TERMUX_BRIDGE ($cli)",
                    )
                }
            }
        }
    }

    private suspend fun checkBridgeProviders(): HealthDiagnostic {
        val storage = SecureStorage.getInstance(context)
        if (storage.getSelectedBackendStrategy() != BackendStrategy.TERMUX_BRIDGE) {
            return HealthDiagnostic(LABEL_BRIDGE_PROVIDERS, HealthStatus.HIDDEN)
        }
        val orchestrator = TermuxOrchestrator.getInstance(context)
        val providers = orchestrator.fetchProviders()
            ?: return HealthDiagnostic(
                LABEL_BRIDGE_PROVIDERS,
                HealthStatus.FAIL,
                DETAIL_BRIDGE_UNREACHABLE,
            )
        // Available count only — no provider id leaks beyond the structural list
        val summary = providers.joinToString(separator = ", ") { p ->
            if (p.available) "${p.id}:ok" else "${p.id}:off"
        }
        val anyAvailable = providers.any { it.available }
        return HealthDiagnostic(
            LABEL_BRIDGE_PROVIDERS,
            if (anyAvailable) HealthStatus.OK else HealthStatus.WARN,
            summary,
        )
    }

    companion object {
        // Phase 12 §10: ScreenReaderService FQN is duplicated from
        // AlwaysOnRoute.kt; main/ source cannot reference the fdroid-only
        // class directly. Keep the two strings in sync if either renames.
        private const val A11Y_SERVICE_FQN = "com.aikeyboard.app.a11y.ScreenReaderService"

        // Structural labels (English; not localized — these strings show up
        // in the copy-to-clipboard report and need to round-trip through
        // text channels without locale ambiguity).
        const val LABEL_IME_ENABLED = "IME enabled"
        const val LABEL_IME_SELECTED = "IME selected as current"
        const val LABEL_A11Y = "AccessibilityService bound"
        const val LABEL_BACKEND = "Backend configured"
        const val LABEL_BRIDGE_PROVIDERS = "Bridge providers"

        private const val DETAIL_IMM_UNAVAILABLE = "InputMethodManager unavailable"
        private const val DETAIL_IME_DISABLED = "Enable AI Keyboard in System Settings → Languages & input"
        private const val DETAIL_IME_NOT_SELECTED = "Switch to AI Keyboard from the IME picker"
        private const val DETAIL_A11Y_OFF = "Optional, not enabled"
        private const val DETAIL_NO_PROVIDER = "No provider configured"
        private const val DETAIL_LOCAL_LAN_INCOMPLETE = "URL or model missing"
        private const val DETAIL_TERMUX_NO_CLI = "No CLI selected"
        private const val DETAIL_BRIDGE_UNREACHABLE = "Bridge not reachable at 127.0.0.1:8787"
    }
}
