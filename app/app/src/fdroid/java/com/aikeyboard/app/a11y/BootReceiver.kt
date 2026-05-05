// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.a11y

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aikeyboard.app.ai.a11y.AlwaysOnProxy
import com.aikeyboard.app.ai.storage.SecureStorage

/**
 * Phase 8 boot receiver. If always-on Read & Respond was on when the device
 * shut down, restart the FGS after the user unlocks (directBootAware="false"
 * defers the receiver until then, which is when Tink-backed SecureStorage
 * becomes readable).
 *
 * The runCatching wrapper is belt-and-suspenders against first-boot edge
 * cases: failing-silent here is preferable to crashing the boot receiver and
 * dragging the rest of the broadcast queue down with us.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching {
            val storage = SecureStorage.getInstance(context.applicationContext)
            if (storage.isAlwaysOnEnabled()) {
                AlwaysOnProxy.start(context.applicationContext)
            }
        }
    }
}
