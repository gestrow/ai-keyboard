// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker.picker

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.aikeyboard.app.ai.sticker.Sticker
import com.aikeyboard.app.latin.R
import java.io.File

class StickerGridAdapter(
    private var packDirResolver: (String) -> File,
    private val onTap: (packId: String, stickerId: String) -> Unit,
) : RecyclerView.Adapter<StickerGridAdapter.VH>() {

    private var data: List<Pair<String, Sticker>> = emptyList()

    fun update(items: List<Pair<String, Sticker>>, resolver: (String) -> File) {
        packDirResolver = resolver
        data = items
        @Suppress("NotifyDataSetChanged") // Phase 9a: tiny grids, DiffUtil deferred to Phase 12.
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.sticker_picker_item, parent, false) as ImageView
        return VH(view)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (packId, sticker) = data[position]
        val file = File(packDirResolver(packId), sticker.fileName)
        // Decoding on the binder thread (== main, because update() is called from main).
        // ≤100KB WebPs decode quickly; the picker open is a single user gesture, not a
        // typing-loop bottleneck. Phase 12 can add an LRU bitmap cache if profiling shows hitches.
        val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        holder.image.setImageBitmap(bitmap)
        holder.image.setOnClickListener { onTap(packId, sticker.id) }
    }

    class VH(val image: ImageView) : RecyclerView.ViewHolder(image)
}
