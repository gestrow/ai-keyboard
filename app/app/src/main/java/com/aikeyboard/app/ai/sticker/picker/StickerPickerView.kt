// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker.picker

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.StringRes
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
    private val tabsView: StickerPackTabsView
    private val errorChip: TextView
    private val adapter: StickerGridAdapter

    private var selectedPackId: String? = null
    // Cache the last-bound packs so onPackSelected can re-bind locally without
    // a round-trip through CommandRowController.
    private var lastPacks: List<StickerPack> = emptyList()
    private var lastResolver: (String) -> File = { File("") }

    private val errorDismiss = Runnable { errorChip.visibility = View.GONE }

    init {
        LayoutInflater.from(context).inflate(R.layout.sticker_picker, this, true)
        grid = findViewById(R.id.sticker_picker_grid)
        emptyView = findViewById(R.id.sticker_picker_empty)
        backBtn = findViewById(R.id.sticker_picker_back)
        importBtn = findViewById(R.id.sticker_picker_import)
        tabsView = findViewById(R.id.sticker_picker_tabs)
        errorChip = findViewById(R.id.sticker_picker_error_chip)

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
        tabsView.onPackSelected = { packId ->
            selectedPackId = packId
            rebindFromCache()
        }

        applyKeyboardTheme()
    }

    fun bind(packs: List<StickerPack>, packDirResolver: (String) -> File) {
        lastPacks = packs
        lastResolver = packDirResolver
        rebindFromCache()
    }

    /**
     * Phase 9b carry-over #1 fix: in-picker error banner. Replaces the
     * `Toast.makeText(ime, ...).show()` path from CommandRowController, which
     * rendered toasts behind the picker (Gravity.BOTTOM offset is too small to
     * clear a ~280dp picker).
     */
    fun showError(@StringRes stringRes: Int) {
        errorChip.removeCallbacks(errorDismiss)
        errorChip.setText(stringRes)
        errorChip.visibility = View.VISIBLE
        errorChip.postDelayed(errorDismiss, ERROR_DURATION_MS)
    }

    private fun rebindFromCache() {
        // Stale-selection guard: if the previously-selected pack was deleted from
        // Settings while the picker was open, the cached id no longer matches any
        // pack. Reset so the default-selection fallback below picks a valid one.
        if (selectedPackId != null && lastPacks.none { it.id == selectedPackId }) {
            selectedPackId = null
        }
        if (selectedPackId == null) {
            selectedPackId = lastPacks.firstOrNull { it.stickers.isNotEmpty() }?.id
                ?: lastPacks.firstOrNull()?.id
        }
        tabsView.bind(lastPacks, lastResolver, selectedPackId)
        val pack = lastPacks.firstOrNull { it.id == selectedPackId }
        val flat = pack?.stickers?.map { selectedPackId!! to it } ?: emptyList()
        adapter.update(flat, lastResolver)
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
        // Error chip uses the action-key (raised attention) background so it stands
        // out from the strip without bringing in a separate theme attr.
        errorChip.setBackgroundColor(colors.get(ColorType.ACTION_KEY_BACKGROUND))
        errorChip.setTextColor(colors.get(ColorType.KEY_TEXT))
    }

    companion object {
        private const val COLUMN_COUNT = 4
        private const val ERROR_DURATION_MS = 2_500L
    }
}
