// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.settings

import android.content.Context
import com.aikeyboard.app.keyboard.internal.KeyboardIconsSet
import com.aikeyboard.app.latin.settings.Settings
import com.aikeyboard.app.latin.utils.SubtypeSettings

// file is meant for making compose previews work

fun initPreview(context: Context) {
    Settings.init(context)
    SubtypeSettings.init(context)
    Settings.getInstance().loadSettings(context)
    SettingsActivity.settingsContainer = SettingsContainer(context)
    KeyboardIconsSet.instance.loadIcons(context)
}
