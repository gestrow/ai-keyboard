// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client

import com.aikeyboard.app.ai.client.remote.RemoteApiBackend
import com.aikeyboard.app.ai.client.termux.TermuxBridgeBackend
import com.aikeyboard.app.ai.storage.SecureStorage
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM coverage for the (strategy, provider) → AiClient dispatch matrix.
 * Exercises every branch with a mocked SecureStorage so the resolver's logic
 * is locked down without dragging Android Keystore into the test path.
 */
class BackendResolverTest {

    @Test
    fun remoteApi_withConfiguredProvider_returnsRemoteApiBackend() {
        val storage = mock(SecureStorage::class.java)
        `when`(storage.getSelectedBackendStrategy()).thenReturn(BackendStrategy.REMOTE_API)
        `when`(storage.getSelectedProvider()).thenReturn(Provider.ANTHROPIC)
        // RemoteApiBackend reads getApiKey at constructor-time only via its lazy isAvailable
        // path; resolve() doesn't call it. Returning a stub key keeps any future eager-read
        // safe.
        `when`(storage.getApiKey(Provider.ANTHROPIC)).thenReturn("sk-ant-stub")

        val client = BackendResolver.resolve(storage)
        assertNotNull(client)
        assertTrue(client is RemoteApiBackend)
    }

    @Test
    fun remoteApi_noProviderSelected_returnsNull() {
        val storage = mock(SecureStorage::class.java)
        `when`(storage.getSelectedBackendStrategy()).thenReturn(BackendStrategy.REMOTE_API)
        `when`(storage.getSelectedProvider()).thenReturn(null)

        assertNull(BackendResolver.resolve(storage))
    }

    @Test
    fun termuxBridge_withCliSelected_returnsTermuxBridgeBackend() {
        val storage = mock(SecureStorage::class.java)
        `when`(storage.getSelectedBackendStrategy()).thenReturn(BackendStrategy.TERMUX_BRIDGE)
        `when`(storage.getSelectedTermuxProvider()).thenReturn("claude")

        val client = BackendResolver.resolve(storage)
        assertNotNull(client)
        assertTrue(client is TermuxBridgeBackend)
    }

    @Test
    fun termuxBridge_noCliSelected_returnsNull() {
        val storage = mock(SecureStorage::class.java)
        `when`(storage.getSelectedBackendStrategy()).thenReturn(BackendStrategy.TERMUX_BRIDGE)
        `when`(storage.getSelectedTermuxProvider()).thenReturn(null)

        assertNull(BackendResolver.resolve(storage))
    }

    @Test
    fun localLan_alwaysReturnsNull_phase10Owns() {
        val storage = mock(SecureStorage::class.java)
        `when`(storage.getSelectedBackendStrategy()).thenReturn(BackendStrategy.LOCAL_LAN)

        assertNull(BackendResolver.resolve(storage))
    }
}
