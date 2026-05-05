// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.a11y

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.aikeyboard.app.ai.a11y.ReadRespondNotificationBuilder

/**
 * Phase 8 receiver fired by the "Copy" action on a completed Read & Respond
 * notification. Lives in src/fdroid/ because it's only ever invoked from the
 * fdroid-only AlwaysOnService's notification posts.
 */
class ClipboardCopyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // EXTRA_TEXT present → "Copy" action; absent → "Dismiss" action. Both
        // dismiss the result notification; only Copy writes to the clipboard.
        // Single receiver keeps the manifest surface tight and avoids a near-
        // identical sibling class.
        val text = intent.getStringExtra(EXTRA_TEXT)
        if (text != null) {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("Read & Respond", text))
            // Android 13+ shows its own clipboard toast; do not double-toast.
        }
        // Constant lives on the builder (in src/main/) — see
        // ReadRespondNotificationBuilder. We don't import AlwaysOnService here.
        NotificationManagerCompat.from(context)
            .cancel(ReadRespondNotificationBuilder.NOTIF_ID_RESULT)
    }

    companion object {
        const val ACTION = "com.aikeyboard.app.ACTION_CLIPBOARD_COPY"
        const val EXTRA_TEXT = "text"
    }
}
