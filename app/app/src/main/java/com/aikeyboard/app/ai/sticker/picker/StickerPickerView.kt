// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker.picker

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aikeyboard.app.ai.sticker.StickerPack
import com.aikeyboard.app.latin.R
import com.aikeyboard.app.latin.common.ColorType
import com.aikeyboard.app.latin.settings.Settings
import java.io.File

class StickerPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Listener {
        /** User tapped a sticker; controller commits via StickerCommitter. */
        fun onStickerSelected(packId: String, stickerId: String)
        /** User tapped the import action. */
        fun onImportRequested()
        /** User tapped the back arrow. */
        fun onDismissRequested()
    }

    var listener: Listener? = null

    private val grid: RecyclerView
    private val emptyView: TextView
    private val backBtn: ImageButton
    private val importBtn: ImageButton
    private val adapter: StickerGridAdapter

    init {
        LayoutInflater.from(context).inflate(R.layout.sticker_picker, this, true)
        grid = findViewById(R.id.sticker_picker_grid)
        emptyView = findViewById(R.id.sticker_picker_empty)
        backBtn = findViewById(R.id.sticker_picker_back)
        importBtn = findViewById(R.id.sticker_picker_import)

        adapter = StickerGridAdapter(
            // No-op resolver until bind() is called; the adapter starts with an empty
            // data list, so onBindViewHolder is never invoked against this stub.
            packDirResolver = { _ -> File("") },
            onTap = { packId, stickerId -> listener?.onStickerSelected(packId, stickerId) },
        )
        grid.layoutManager = GridLayoutManager(context, COLUMN_COUNT)
        grid.adapter = adapter

        backBtn.setOnClickListener { listener?.onDismissRequested() }
        importBtn.setOnClickListener { listener?.onImportRequested() }

        applyKeyboardTheme()
    }

    fun bind(packs: List<StickerPack>, packDirResolver: (String) -> File) {
        val flat = packs.flatMap { p -> p.stickers.map { s -> p.id to s } }
        adapter.update(flat, packDirResolver)
        emptyView.visibility = if (flat.isEmpty()) View.VISIBLE else View.GONE
        grid.visibility = if (flat.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Phase 2.5 keyboard-surface invariant: pull all colors from HeliBoard's
     *  Colors so the picker matches the user's selected keyboard theme. */
    private fun applyKeyboardTheme() {
        val colors = Settings.getValues().mColors
        colors.setBackground(this, ColorType.STRIP_BACKGROUND)
        colors.setColor(backBtn, ColorType.TOOL_BAR_KEY)
        colors.setBackground(backBtn, ColorType.STRIP_BACKGROUND)
        colors.setColor(importBtn, ColorType.TOOL_BAR_KEY)
        colors.setBackground(importBtn, ColorType.STRIP_BACKGROUND)
        emptyView.setTextColor(colors.get(ColorType.KEY_TEXT))
    }

    companion object {
        private const val COLUMN_COUNT = 4
    }
}
