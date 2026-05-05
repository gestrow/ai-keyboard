// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.a11y

import com.aikeyboard.app.latin.R
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Phase 8 JVM tests for the failureKind → R.string mapping. The mapping is a
 * pure switch with no Context dependency, so we can call it directly. Other
 * methods on ReadRespondNotificationBuilder need NotificationManager / Context
 * and are exercised by on-device smoke tests.
 */
class ReadRespondNotificationBuilderTest {

    @Test
    fun failureKindToResId_mapsAllSentinelsToDistinctResources() {
        val sentinels = listOf(
            ReadRespondNotificationBuilder.R_FAILURE_NO_WINDOW,
            ReadRespondNotificationBuilder.R_FAILURE_GENERIC,
            ReadRespondNotificationBuilder.R_FAILURE_NO_CONTEXT,
            ReadRespondNotificationBuilder.R_FAILURE_NO_BACKEND,
            ReadRespondNotificationBuilder.R_FAILURE_STREAM,
        )
        val resolved = sentinels.map { ReadRespondNotificationBuilder.failureKindToResId(it) }
        // All non-zero (resource id 0 means "not found", which would be a build-time bug).
        resolved.forEach { assertNotEquals(0, it, "failureKind sentinel resolved to 0") }
        // No two sentinels collide on the same string. Distinct messages are the
        // whole point of typed sentinels — collapsing them silently regresses UX.
        assertEquals(sentinels.size, resolved.toSet().size, "failureKind sentinels collide on resource ids")
    }

    @Test
    fun failureKindToResId_noWindow_mapsToNoWindowString() {
        assertEquals(
            R.string.ai_read_respond_failure_no_window,
            ReadRespondNotificationBuilder.failureKindToResId(
                ReadRespondNotificationBuilder.R_FAILURE_NO_WINDOW,
            ),
        )
    }

    @Test
    fun failureKindToResId_generic_mapsToGenericString() {
        assertEquals(
            R.string.ai_read_respond_failure_generic,
            ReadRespondNotificationBuilder.failureKindToResId(
                ReadRespondNotificationBuilder.R_FAILURE_GENERIC,
            ),
        )
    }

    @Test
    fun failureKindToResId_noContext_mapsToNoContextString() {
        assertEquals(
            R.string.ai_read_respond_failure_no_context,
            ReadRespondNotificationBuilder.failureKindToResId(
                ReadRespondNotificationBuilder.R_FAILURE_NO_CONTEXT,
            ),
        )
    }

    @Test
    fun failureKindToResId_noBackend_mapsToNoBackendString() {
        assertEquals(
            R.string.ai_read_respond_failure_no_backend,
            ReadRespondNotificationBuilder.failureKindToResId(
                ReadRespondNotificationBuilder.R_FAILURE_NO_BACKEND,
            ),
        )
    }

    @Test
    fun failureKindToResId_stream_mapsToStreamString() {
        assertEquals(
            R.string.ai_read_respond_failure_stream,
            ReadRespondNotificationBuilder.failureKindToResId(
                ReadRespondNotificationBuilder.R_FAILURE_STREAM,
            ),
        )
    }

    @Test
    fun failureKindToResId_unknownSentinel_fallsBackToGeneric() {
        // Defensive: an out-of-range sentinel from a future caller shouldn't
        // crash; it should resolve to the generic fallback. Picks a value
        // outside the 0..4 range used today.
        val resId = ReadRespondNotificationBuilder.failureKindToResId(99)
        assertTrue(resId != 0, "fallback resolved to 0")
        assertEquals(R.string.ai_read_respond_failure_generic, resId)
    }
}
