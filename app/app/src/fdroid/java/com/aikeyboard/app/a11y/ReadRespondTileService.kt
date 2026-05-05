// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.a11y

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.aikeyboard.app.ai.storage.SecureStorage

/**
 * Phase 8 Quick Settings tile. Visible only on fdroid builds (the manifest
 * entry is in src/fdroid/AndroidManifest.xml).
 *
 * Tile state mirrors `alwaysOnEnabled`: ACTIVE if always-on is on,
 * INACTIVE otherwise. Tap dispatches:
 *   - if always-on is on: send ACTION_READ_RESPOND to AlwaysOnService
 *   - if always-on is off: open AI Settings → Always-On screen so the user
 *     can flip the toggle (we don't auto-flip from a tile tap because the
 *     toggle requires permission flows that are awkward without an Activity)
 */
class ReadRespondTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val enabled = SecureStorage.getInstance(applicationContext).isAlwaysOnEnabled()
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val storage = SecureStorage.getInstance(applicationContext)
        if (storage.isAlwaysOnEnabled()) {
            val intent = Intent(applicationContext, AlwaysOnService::class.java).apply {
                action = AlwaysOnService.ACTION_READ_RESPOND
            }
            applicationContext.startForegroundService(intent)
        } else {
            // Open the always-on settings screen via the existing AiSettingsActivity.
            // FLAG_ACTIVITY_NEW_TASK because TileService isn't an Activity context.
            val intent = Intent().apply {
                setClassName(
                    applicationContext.packageName,
                    "com.aikeyboard.app.ai.ui.AiSettingsActivity",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_DEEP_LINK_ROUTE, "always-on")
            }
            startActivityAndCollapseCompat(intent)
        }
    }

    /**
     * startActivityAndCollapse(Intent) throws UnsupportedOperationException on
     * apps targeting API 34+. The PendingIntent overload was added in API 34
     * (UpsideDownCake), so we branch on SDK_INT — Intent on 29-33, PendingIntent
     * on 34+. Both forms collapse the QS panel and start the activity.
     */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun startActivityAndCollapseCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                applicationContext, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        const val EXTRA_DEEP_LINK_ROUTE = "deep_link_route"
    }
}
