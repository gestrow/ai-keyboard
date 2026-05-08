// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker.picker

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aikeyboard.app.ai.sticker.StickerPack
import com.aikeyboard.app.latin.R
import com.aikeyboard.app.latin.common.ColorType
import com.aikeyboard.app.latin.settings.Settings
import java.io.File

/**
 * Phase 9b: horizontal strip of pack-tray-icon buttons sitting above the picker
 * grid. One button per pack; tap → onPackSelected fires with that pack's id.
 */
class StickerPackTabsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    /** Set by StickerPickerView in its init {} block. */
    var onPackSelected: ((packId: String) -> Unit)? = null

    private val tabAdapter = TabAdapter(onTap = { packId -> onPackSelected?.invoke(packId) })

    init {
        layoutManager = LinearLayoutManager(context, HORIZONTAL, false)
        adapter = tabAdapter
        applyKeyboardTheme()
    }

    fun bind(packs: List<StickerPack>, packDirResolver: (String) -> File, selectedPackId: String?) {
        tabAdapter.update(packs, packDirResolver, selectedPackId)
    }

    private fun applyKeyboardTheme() {
        val colors = Settings.getValues().mColors
        colors.setBackground(this, ColorType.STRIP_BACKGROUND)
    }

    /**
     * Static-nested (NOT `inner`) so the adapter doesn't capture an implicit
     * reference to the outer StickerPackTabsView. The IME recreates the input
     * view on theme changes, so old picker view hierarchies should be GC-eligible
     * promptly. An `inner` adapter would block GC via `this$0` until the adapter
     * itself is collected.
     */
    private class TabAdapter(
        private val onTap: (packId: String) -> Unit,
    ) : Adapter<TabVH>() {
        private var packs: List<StickerPack> = emptyList()
        private var resolver: (String) -> File = { File("") }
        private var selectedPackId: String? = null

        fun update(packs: List<StickerPack>, resolver: (String) -> File, selectedPackId: String?) {
            this.packs = packs
            this.resolver = resolver
            this.selectedPackId = selectedPackId
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = packs.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabVH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.sticker_pack_tab, parent, false) as ImageButton
            return TabVH(view)
        }

        override fun onBindViewHolder(holder: TabVH, position: Int) {
            val pack = packs[position]
            val tray = pack.trayIconFile?.let { File(resolver(pack.id), it) }
            if (tray != null && tray.exists()) {
                holder.btn.setImageBitmap(BitmapFactory.decodeFile(tray.absolutePath))
            } else {
                holder.btn.setImageResource(R.drawable.ic_sticker)
            }
            holder.btn.alpha = if (pack.id == selectedPackId) 1.0f else 0.5f
            holder.btn.contentDescription = pack.name
            holder.btn.setOnClickListener { onTap(pack.id) }
        }
    }

    private class TabVH(val btn: ImageButton) : ViewHolder(btn)
}
