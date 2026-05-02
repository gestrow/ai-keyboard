// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.commandrow

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.PopupMenu
import com.aikeyboard.app.ai.persona.Persona
import com.aikeyboard.app.ai.storage.SecureStorage
import com.aikeyboard.app.ai.ui.AiSettingsActivity

class CommandRowController(
    private val context: Context,
    private val view: CommandRowView,
    private val storage: SecureStorage,
) : CommandRowView.Listener {

    private var aiModeEnabled: Boolean = false

    init {
        view.listener = this
        refreshActivePersona()
        view.setAiToggleState(aiModeEnabled)
    }

    fun refreshActivePersona() {
        val activeId = storage.getActivePersonaId()
        val personas = storage.getPersonas()
        val active = personas.firstOrNull { it.id == activeId } ?: personas.first()
        view.bindActivePersona(active)
    }

    override fun onPersonaSelectorTap(anchor: View) {
        val personas = storage.getPersonas()
        val popup = PopupMenu(context, anchor)
        personas.forEachIndexed { index, persona ->
            popup.menu.add(0, index, index, persona.name)
        }
        popup.setOnMenuItemClickListener { item ->
            val selected = personas.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
            storage.setActivePersonaId(selected.id)
            view.bindActivePersona(selected)
            true
        }
        popup.show()
    }

    override fun onAiToggleTap() {
        aiModeEnabled = !aiModeEnabled
        view.setAiToggleState(aiModeEnabled)
    }

    override fun onReadRespondTap() {
        Log.d(TAG, "Read & Respond tapped (Phase 7)")
    }

    override fun onStickerTap() {
        Log.d(TAG, "Sticker tab tapped (Phase 9)")
    }

    override fun onSettingsTap() {
        launchSettings()
    }

    override fun onSettingsLongPress() {
        launchSettings()
    }

    private fun launchSettings() {
        val intent = Intent(context, AiSettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    companion object {
        private const val TAG = "AiCommandRow"
    }
}
