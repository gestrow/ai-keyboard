// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.storage

import com.aikeyboard.app.ai.persona.Persona
import kotlinx.serialization.Serializable

/**
 * Plain envelope serialized as JSON, then sealed by Tink AEAD into the on-disk
 * blob managed by {@link SecureStorage}. Internal so the JVM unit tests can
 * exercise the envelope round-trip without depending on Android Keystore.
 */
@Serializable
internal data class SecureData(
    val personas: List<Persona> = emptyList(),
    val activePersonaId: String? = null,
    val apiKeys: Map<String, String> = emptyMap(),
    // Storage key of the user's preferred provider for Rewrite. UI to set it explicitly
    // arrives in Phase 6; until then RemoteApiBackend falls back to "first configured wins".
    val selectedProviderKey: String? = null,
    // Phase 6: which strategy is "active for Rewrite". null defaults to REMOTE_API
    // (back-compat for users upgrading from Phase 3b/5b).
    val selectedBackendStrategy: String? = null,
    // Phase 6: when strategy == TERMUX_BRIDGE, which bridge /providers id is active.
    // String to stay loose-coupled from any enum (Codex in Phase 11 needs no schema change).
    val selectedTermuxProvider: String? = null,
    // Phase 7b: Read & Respond sends screen content to the active backend
    // (cloud or LAN/Termux). Default false — first Read & Respond tap shows
    // a consent activity and only flips to true on Accept. Persisting a
    // single boolean (not per-backend) is intentional: the user already
    // chose the backend in Settings → Backends; this flag captures the
    // separate "OK to send screen content to wherever I configured" decision.
    val readRespondConsented: Boolean = false,
    // Phase 8: Always-On Read & Respond toggle. Default false. When true,
    // AlwaysOnService runs as FGS, the QS tile is ACTIVE, and BootReceiver
    // restarts the service after reboot. Flipping to true requires a11y
    // permission, POST_NOTIFICATIONS (Android 13+), and readRespondConsented.
    val alwaysOnEnabled: Boolean = false,
    // Phase 10: Local LAN backend configuration. All default-valued so existing
    // SecureData blobs decode without migration. localLanApiFormat is String?
    // (not the enum directly) for the same null-defaults-to-OLLAMA pattern as
    // selectedBackendStrategy.
    val localLanBaseUrl: String = "",
    val localLanApiFormat: String? = null,
    val localLanApiKey: String = "",
    val localLanModelName: String = "",
)
