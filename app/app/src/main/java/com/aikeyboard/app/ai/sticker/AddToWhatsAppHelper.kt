// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import android.content.Context
import android.content.Intent

object AddToWhatsAppHelper {

    private const val ACTION = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
    private const val EXTRA_ID = "sticker_pack_id"
    private const val EXTRA_AUTHORITY = "sticker_pack_authority"
    private const val EXTRA_NAME = "sticker_pack_name"

    private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    enum class WhatsAppStatus { CONSUMER_ONLY, BUSINESS_ONLY, BOTH, NONE }

    fun status(context: Context): WhatsAppStatus =
        statusFromInstalled(WHATSAPP_PACKAGES.filter { isInstalled(context, it) })

    /** Pure helper: extracted for unit testing. */
    @JvmStatic
    internal fun statusFromInstalled(installed: List<String>): WhatsAppStatus = when {
        "com.whatsapp" in installed && "com.whatsapp.w4b" in installed -> WhatsAppStatus.BOTH
        "com.whatsapp" in installed -> WhatsAppStatus.CONSUMER_ONLY
        "com.whatsapp.w4b" in installed -> WhatsAppStatus.BUSINESS_ONLY
        else -> WhatsAppStatus.NONE
    }

    /** Build the intent. Doesn't call startActivity — caller (a Compose route)
     *  uses rememberLauncherForActivityResult(StartActivityForResult()) to fire
     *  it and observe the result. */
    fun buildIntent(packId: String, authority: String, packName: String): Intent =
        Intent(ACTION).apply {
            putExtra(EXTRA_ID, packId)
            putExtra(EXTRA_AUTHORITY, authority)
            putExtra(EXTRA_NAME, packName)
        }

    private fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)
}
