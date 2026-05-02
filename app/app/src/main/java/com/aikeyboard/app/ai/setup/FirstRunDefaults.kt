// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.setup

import android.content.Context
import androidx.core.content.edit
import com.aikeyboard.app.latin.settings.Settings
import com.aikeyboard.app.latin.utils.ToolbarMode
import com.aikeyboard.app.latin.utils.prefs

/**
 * One-shot first-run defaults applied on top of HeliBoard's. Each default flips a HeliBoard
 * pref once, then sets a flag so subsequent launches respect any user change. Sharing
 * HeliBoard's prefs file (the device-protected `<packageName>_preferences`) means the
 * `OnSharedPreferenceChangeListener` already wired up in `Settings` re-renders for free.
 *
 * Existing testers who first launched a Phase 2 build have already materialized HeliBoard's
 * `EXPANDABLE` default into the prefs file (or implicitly carry it as the absent-key fallback).
 * The flag below is set fresh per install, so they keep `EXPANDABLE` until they flip the
 * toggle themselves — fresh installs from Phase 2.5 onward get `SUGGESTION_STRIP`.
 */
object FirstRunDefaults {
    private const val KEY_TOOLBAR_DEFAULT_APPLIED = "ai_keyboard_toolbar_default_applied"

    fun apply(context: Context) {
        val prefs = context.prefs()
        if (prefs.getBoolean(KEY_TOOLBAR_DEFAULT_APPLIED, false)) return
        prefs.edit {
            putString(Settings.PREF_TOOLBAR_MODE, ToolbarMode.SUGGESTION_STRIP.name)
            putBoolean(KEY_TOOLBAR_DEFAULT_APPLIED, true)
        }
    }
}
