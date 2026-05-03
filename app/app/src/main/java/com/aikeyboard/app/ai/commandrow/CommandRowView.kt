// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.commandrow

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.aikeyboard.app.ai.persona.Persona
import com.aikeyboard.app.latin.R
import com.aikeyboard.app.latin.common.ColorType
import com.aikeyboard.app.latin.settings.Settings

class CommandRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onPersonaSelectorTap(anchor: View)
        fun onRewriteTap()
        fun onReadRespondTap()
        fun onStickerTap()
        fun onSettingsLongPress()
        fun onSettingsTap()
    }

    var listener: Listener? = null

    private val personaSelector: View
    private val personaLabel: TextView
    private val personaChevron: TextView
    private val rewriteBtn: ImageButton
    private val readRespondBtn: ImageButton
    private val stickerBtn: ImageButton
    private val settingsBtn: ImageButton

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        LayoutInflater.from(context).inflate(R.layout.command_row, this, true)

        personaSelector = findViewById(R.id.cmd_persona_selector)
        personaLabel = findViewById(R.id.cmd_persona_label)
        personaChevron = findViewById(R.id.cmd_persona_chevron)
        rewriteBtn = findViewById(R.id.cmd_rewrite)
        readRespondBtn = findViewById(R.id.cmd_read_respond)
        stickerBtn = findViewById(R.id.cmd_sticker)
        settingsBtn = findViewById(R.id.cmd_settings)

        personaSelector.setOnClickListener { listener?.onPersonaSelectorTap(personaSelector) }
        rewriteBtn.setOnClickListener { listener?.onRewriteTap() }
        readRespondBtn.setOnClickListener { listener?.onReadRespondTap() }
        stickerBtn.setOnClickListener { listener?.onStickerTap() }
        settingsBtn.setOnClickListener { listener?.onSettingsTap() }
        settingsBtn.setOnLongClickListener {
            listener?.onSettingsLongPress()
            true
        }

        applyKeyboardTheme()
    }

    /**
     * Mirrors what HeliBoard's SuggestionStripView does so the command row blends in with
     * whichever keyboard theme is active rather than relying on the app-level Material attrs
     * (which resolve against the IME's host theme and look transparent on most key themes).
     */
    private fun applyKeyboardTheme() {
        val colors = Settings.getValues().mColors
        colors.setBackground(this, ColorType.STRIP_BACKGROUND)

        val keyTextColor = colors.get(ColorType.KEY_TEXT)
        personaLabel.setTextColor(keyTextColor)
        personaChevron.setTextColor(keyTextColor)

        colors.setColor(rewriteBtn, ColorType.TOOL_BAR_KEY)
        colors.setBackground(rewriteBtn, ColorType.STRIP_BACKGROUND)
        colors.setColor(readRespondBtn, ColorType.TOOL_BAR_KEY)
        colors.setBackground(readRespondBtn, ColorType.STRIP_BACKGROUND)
        colors.setColor(stickerBtn, ColorType.TOOL_BAR_KEY)
        colors.setBackground(stickerBtn, ColorType.STRIP_BACKGROUND)
        colors.setColor(settingsBtn, ColorType.TOOL_BAR_KEY)
        colors.setBackground(settingsBtn, ColorType.STRIP_BACKGROUND)
    }

    fun bindActivePersona(persona: Persona) {
        personaLabel.text = persona.name
    }
}
