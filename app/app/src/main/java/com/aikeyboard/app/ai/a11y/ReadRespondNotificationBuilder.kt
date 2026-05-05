// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.a11y

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aikeyboard.app.latin.R

/**
 * Phase 8 notification builder. Lives in src/main/ because notifications
 * themselves are platform-agnostic; only the SERVICE that posts them is
 * fdroid-only. The play APK ships this class but never instantiates it
 * (R8 keep rule pins it observable in dex).
 *
 * Cross-flavor intent construction: AlwaysOnService and ClipboardCopyReceiver
 * are fdroid-only, but this builder must compile in both flavors. All
 * intents to those targets are constructed via Intent.setClassName(packageName,
 * "<FQN>") with hardcoded string FQNs — never `::class.java`, which would
 * fail to compile in the play flavor.
 *
 * Privacy: this builder NEVER logs streamed text, accumulated text, or any
 * user-screen content. Notification IDs and structural metadata only.
 */
object ReadRespondNotificationBuilder {

    const val CHANNEL_PERSISTENT = "read_respond_persistent"   // IMPORTANCE_LOW
    const val CHANNEL_RESULTS = "read_respond_results"         // IMPORTANCE_HIGH

    // Notification IDs. Constants live HERE (in src/main/) rather than on
    // AlwaysOnService (in src/fdroid/) so that ClipboardCopyReceiver in fdroid
    // can reference them via a main-classpath name without forcing main code
    // to import a fdroid-only class.
    const val NOTIF_ID_PERSISTENT = 1001
    const val NOTIF_ID_RESULT = 1002

    // Failure-kind sentinels. AlwaysOnService passes these to postFailureChip;
    // postFailureChip's internal mapping resolves them to localized R.string IDs.
    const val R_FAILURE_NO_WINDOW = 0
    const val R_FAILURE_GENERIC = 1
    const val R_FAILURE_NO_CONTEXT = 2
    const val R_FAILURE_NO_BACKEND = 3
    const val R_FAILURE_STREAM = 4

    // Intent action / receiver / service FQNs as string literals so this file
    // compiles in both flavors. AlwaysOnService and ClipboardCopyReceiver only
    // exist in src/fdroid/; in the play APK these intents are unreachable
    // because the service is never started (AlwaysOnProxy stub no-ops).
    private const val ACTION_READ_RESPOND = "com.aikeyboard.app.ACTION_READ_RESPOND"
    private const val ACTION_CLIPBOARD_COPY = "com.aikeyboard.app.ACTION_CLIPBOARD_COPY"
    private const val FQN_ALWAYS_ON_SERVICE = "com.aikeyboard.app.a11y.AlwaysOnService"
    private const val FQN_CLIPBOARD_RECEIVER = "com.aikeyboard.app.a11y.ClipboardCopyReceiver"
    private const val FQN_AI_SETTINGS_ACTIVITY = "com.aikeyboard.app.ai.ui.AiSettingsActivity"
    private const val EXTRA_DEEP_LINK_ROUTE = "deep_link_route"
    private const val EXTRA_TEXT = "text"

    // Request codes for PendingIntents — distinct so the system doesn't merge them.
    private const val RC_CHIP_OPEN_SETTINGS = 100
    private const val RC_CHIP_TRIGGER = 101
    private const val RC_RESULT_COPY = 200
    private const val RC_RESULT_DISMISS = 201
    private const val RC_RESULT_OPEN_A11Y = 202
    private const val RC_RESULT_OPEN_CONSENT = 203

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val persistent = NotificationChannel(
            CHANNEL_PERSISTENT,
            context.getString(R.string.ai_always_on_channel_persistent),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.ai_always_on_channel_persistent_desc)
            setShowBadge(false)
        }
        val results = NotificationChannel(
            CHANNEL_RESULTS,
            context.getString(R.string.ai_always_on_channel_results),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.ai_always_on_channel_results_desc)
            setShowBadge(true)
        }
        nm.createNotificationChannel(persistent)
        nm.createNotificationChannel(results)
    }

    fun buildPersistentChip(context: Context): Notification {
        val openSettings = openAlwaysOnSettingsIntent(context)
        val openSettingsPi = PendingIntent.getActivity(
            context, RC_CHIP_OPEN_SETTINGS, openSettings,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val triggerPi = PendingIntent.getService(
            context, RC_CHIP_TRIGGER, readRespondServiceIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_PERSISTENT)
            .setSmallIcon(R.drawable.ic_read_respond)
            .setContentTitle(context.getString(R.string.ai_always_on_chip_title))
            .setContentText(context.getString(R.string.ai_always_on_chip_body))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openSettingsPi)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_read_respond,
                    context.getString(R.string.ai_always_on_chip_action),
                    triggerPi,
                ).build()
            )
            .build()
    }

    fun postStreaming(context: Context, text: String) {
        val notif = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_read_respond)
            .setContentTitle(context.getString(R.string.ai_read_respond_result_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(redactedPublicVersion(context))
            .build()
        notifySafely(context, NOTIF_ID_RESULT, notif)
    }

    fun postCompleted(context: Context, text: String) {
        val copyIntent = Intent().apply {
            setClassName(context.packageName, FQN_CLIPBOARD_RECEIVER)
            action = ACTION_CLIPBOARD_COPY
            putExtra(EXTRA_TEXT, text)
        }
        val copyPi = PendingIntent.getBroadcast(
            context, RC_RESULT_COPY, copyIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Dismiss: re-broadcast the same receiver with no EXTRA_TEXT — the
        // receiver short-circuits on missing extras and just cancels the
        // result notification. Avoids introducing a second receiver class
        // for the trivial "tap to dismiss without copying" path.
        val dismissIntent = Intent().apply {
            setClassName(context.packageName, FQN_CLIPBOARD_RECEIVER)
            action = ACTION_CLIPBOARD_COPY
        }
        val dismissPi = PendingIntent.getBroadcast(
            context, RC_RESULT_DISMISS, dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_read_respond)
            .setContentTitle(context.getString(R.string.ai_read_respond_result_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(redactedPublicVersion(context))
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_read_respond,
                    context.getString(R.string.ai_read_respond_action_copy),
                    copyPi,
                ).build()
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_read_respond,
                    context.getString(R.string.ai_read_respond_action_dismiss),
                    dismissPi,
                ).build()
            )
            .build()
        notifySafely(context, NOTIF_ID_RESULT, notif)
    }

    fun postFailureChip(context: Context, failureKind: Int) {
        val msg = context.getString(failureKindToResId(failureKind))
        val notif = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_read_respond)
            .setContentTitle(context.getString(R.string.ai_read_respond_result_title))
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(redactedPublicVersion(context))
            .build()
        notifySafely(context, NOTIF_ID_RESULT, notif)
    }

    fun postConsentRequired(context: Context) {
        val openSettingsPi = PendingIntent.getActivity(
            context, RC_RESULT_OPEN_CONSENT, openAlwaysOnSettingsIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val msg = context.getString(R.string.ai_read_respond_failure_consent_required)
        val notif = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_read_respond)
            .setContentTitle(context.getString(R.string.ai_read_respond_result_title))
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(redactedPublicVersion(context))
            .setContentIntent(openSettingsPi)
            .build()
        notifySafely(context, NOTIF_ID_RESULT, notif)
    }

    fun postServiceNotEnabled(context: Context) {
        val msg = context.getString(R.string.ai_read_respond_failure_a11y_required)
        val openA11yPi = PendingIntent.getActivity(
            context, RC_RESULT_OPEN_A11Y,
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_read_respond)
            .setContentTitle(context.getString(R.string.ai_read_respond_result_title))
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(redactedPublicVersion(context))
            .setContentIntent(openA11yPi)
            .build()
        notifySafely(context, NOTIF_ID_RESULT, notif)
    }

    /**
     * Pure switch: failureKind sentinel → string resource id. Extracted so JVM
     * unit tests can assert the mapping is total without instantiating a
     * Context. New sentinels added here MUST also be added to the unit test.
     */
    internal fun failureKindToResId(failureKind: Int): Int = when (failureKind) {
        R_FAILURE_NO_WINDOW -> R.string.ai_read_respond_failure_no_window
        R_FAILURE_GENERIC -> R.string.ai_read_respond_failure_generic
        R_FAILURE_NO_CONTEXT -> R.string.ai_read_respond_failure_no_context
        R_FAILURE_NO_BACKEND -> R.string.ai_read_respond_failure_no_backend
        R_FAILURE_STREAM -> R.string.ai_read_respond_failure_stream
        else -> R.string.ai_read_respond_failure_generic
    }

    /**
     * AlwaysOnRoute gates flipping the toggle on POST_NOTIFICATIONS being
     * granted (Android 13+); BootReceiver only restarts when the user had
     * already cleared that gate; the play APK's no-op proxy never reaches
     * this code path. SecurityException is caught so a permission revocation
     * mid-session degrades to a silent failure rather than crashing the FGS.
     */
    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun notifySafely(context: Context, id: Int, notif: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notif)
        } catch (_: SecurityException) {
            // Permission revoked between gate check and post; nothing user-actionable.
        }
    }

    private fun openAlwaysOnSettingsIntent(context: Context): Intent =
        Intent().apply {
            setClassName(context.packageName, FQN_AI_SETTINGS_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_DEEP_LINK_ROUTE, "always-on")
        }

    private fun readRespondServiceIntent(context: Context): Intent =
        Intent().apply {
            setClassName(context.packageName, FQN_ALWAYS_ON_SERVICE)
            action = ACTION_READ_RESPOND
        }

    private fun redactedPublicVersion(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_read_respond)
            .setContentTitle(context.getString(R.string.ai_read_respond_result_title))
            .setContentText(context.getString(R.string.ai_always_on_chip_body))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
}
