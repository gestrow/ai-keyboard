// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client

import com.aikeyboard.app.ai.client.remote.RemoteApiBackend
import com.aikeyboard.app.ai.client.termux.TermuxBridgeBackend
import com.aikeyboard.app.ai.storage.SecureStorage

/**
 * Single dispatch site for the active Rewrite backend. Reads the user's
 * `selectedBackendStrategy` and the strategy-specific selection from
 * {@link SecureStorage}, returning the matching {@link AiClient} or null
 * if nothing is currently usable (no API keys configured, or Termux selected
 * but no provider picked). Caller toasts a friendly "configure a backend"
 * message on null.
 *
 * Stays trivial by design — a single-call-site dispatch, not an abstraction
 * layer. {@link BackendResolverTest} exercises each branch with mocked storage.
 */
object BackendResolver {
    fun resolve(storage: SecureStorage): AiClient? =
        when (storage.getSelectedBackendStrategy()) {
            BackendStrategy.REMOTE_API -> {
                val provider = storage.getSelectedProvider() ?: return null
                RemoteApiBackend(provider, storage)
            }
            BackendStrategy.TERMUX_BRIDGE -> {
                val cli = storage.getSelectedTermuxProvider() ?: return null
                TermuxBridgeBackend(cli)
            }
            BackendStrategy.LOCAL_LAN -> null  // Phase 10
        }
}
