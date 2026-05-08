// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.aikeyboard.app.latin.utils.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class StickerStorage @VisibleForTesting internal constructor(
    private val rootDir: File,
) {

    @Volatile private var cached: StickerManifest? = null

    private val _changes = MutableStateFlow(0L)
    /**
     * Monotonic mutation counter. Compose screens collect this so any change to the
     * manifest (import, rename, delete) from any route reaches every screen without
     * the routes hand-rolling refresh ticks.
     */
    val changes: StateFlow<Long> get() = _changes

    fun getManifest(): StickerManifest = cached ?: synchronized(this) {
        cached ?: load().also { cached = it }
    }

    fun renamePack(packId: String, newName: String) {
        update { manifest ->
            manifest.copy(packs = manifest.packs.map {
                if (it.id == packId) it.copy(name = newName) else it
            })
        }
    }

    fun createPack(name: String): StickerPack {
        val pack = StickerPack(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAt = System.currentTimeMillis(),
        )
        File(rootDir, "packs/${pack.id}").mkdirs()
        update { it.copy(packs = it.packs + pack) }
        return pack
    }

    fun deletePack(packId: String) {
        File(rootDir, "packs/$packId").deleteRecursively()
        update { it.copy(packs = it.packs.filterNot { p -> p.id == packId }) }
    }

    fun deleteSticker(packId: String, stickerId: String) {
        val manifest = getManifest()
        val pack = manifest.packs.firstOrNull { it.id == packId } ?: return
        val sticker = pack.stickers.firstOrNull { it.id == stickerId } ?: return
        File(rootDir, "packs/$packId/${sticker.fileName}").delete()
        update {
            it.copy(packs = it.packs.map { p ->
                if (p.id != packId) p
                else p.copy(stickers = p.stickers.filterNot { s -> s.id == stickerId })
            })
        }
    }

    fun addSticker(packId: String, stickerId: String, fileName: String) {
        update {
            it.copy(packs = it.packs.map { p ->
                if (p.id != packId) p
                else p.copy(stickers = p.stickers + Sticker(stickerId, fileName))
            })
        }
    }

    fun setTrayIcon(packId: String, fileName: String) {
        update { manifest ->
            manifest.copy(packs = manifest.packs.map {
                if (it.id == packId) it.copy(trayIconFile = fileName) else it
            })
        }
    }

    fun setPublisher(packId: String, publisher: String) {
        update { manifest ->
            manifest.copy(packs = manifest.packs.map {
                if (it.id == packId) it.copy(publisher = publisher) else it
            })
        }
    }

    fun setStickerEmojis(packId: String, stickerId: String, emojis: List<String>) {
        update { manifest ->
            manifest.copy(packs = manifest.packs.map { p ->
                if (p.id != packId) p
                else p.copy(stickers = p.stickers.map { s ->
                    if (s.id == stickerId) s.copy(emojis = emojis) else s
                })
            })
        }
    }

    /** Where StickerNormalizer should write a new sticker file. The pack directory
     *  is created by createPack; callers can re-mkdirs the parent for orphan recovery. */
    fun stickerFile(packId: String, fileName: String): File =
        File(rootDir, "packs/$packId/$fileName")

    /** Where TrayIconNormalizer should write a new tray icon. Sibling of [stickerFile]. */
    fun trayIconFile(packId: String, fileName: String): File =
        File(rootDir, "packs/$packId/$fileName")

    /** First-import helper. Returns the existing first pack, creating one with [defaultName] if absent. */
    fun ensureDefaultPack(defaultName: String): StickerPack {
        val existing = getManifest().packs.firstOrNull()
        if (existing != null) return existing
        return createPack(defaultName)
    }

    private fun update(transform: (StickerManifest) -> StickerManifest) {
        synchronized(this) {
            val current = getManifest()
            val transformed = transform(current)
            // Phase 9b: bump imageDataVersion for each pack whose content-bearing tuple
            // changed. WhatsApp re-fetches a pack when this differs from its cache.
            val priorById = current.packs.associateBy { it.id }
            val next = transformed.copy(packs = transformed.packs.map { newPack ->
                val priorPack = priorById[newPack.id]
                if (priorPack != null && !packsContentEqual(priorPack, newPack)) {
                    newPack.copy(imageDataVersion = newPack.imageDataVersion + 1)
                } else {
                    newPack
                }
            })
            persist(next)
            cached = next
            _changes.value += 1
        }
    }

    /** Two packs are content-equal when their WhatsApp-visible state matches. The
     *  imageDataVersion field itself and createdAt do NOT contribute (otherwise
     *  the bump would cascade indefinitely / be a no-op). */
    private fun packsContentEqual(a: StickerPack, b: StickerPack): Boolean =
        a.name == b.name &&
            a.publisher == b.publisher &&
            a.trayIconFile == b.trayIconFile &&
            a.avoidCache == b.avoidCache &&
            a.stickers == b.stickers

    private fun load(): StickerManifest {
        val file = File(rootDir, MANIFEST_FILE)
        if (!file.exists()) return StickerManifest()
        return runCatching { JSON.decodeFromString<StickerManifest>(file.readText()) }
            .getOrElse {
                // Privacy: log type only, never the file's contents.
                // Per Phase 3a precedent: do NOT delete the corrupt file — preserves
                // user data on failure paths.
                // runCatching guards the log call so JVM unit tests where android.util.Log
                // is stubbed don't crash the host process under Stub! exceptions.
                runCatching {
                    Log.w(TAG, "Manifest decode failed: ${it.javaClass.simpleName}; resetting in memory")
                }
                StickerManifest()
            }
    }

    private fun persist(manifest: StickerManifest) {
        rootDir.mkdirs()
        val tmp = File(rootDir, "$MANIFEST_FILE.tmp")
        tmp.writeText(JSON.encodeToString(StickerManifest.serializer(), manifest))
        val target = File(rootDir, MANIFEST_FILE)
        if (!tmp.renameTo(target)) {
            // renameTo can fail across filesystem boundaries; copy fallback.
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "StickerStorage"
        private const val MANIFEST_FILE = "manifest.json"
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        @Volatile private var instance: StickerStorage? = null

        fun getInstance(context: Context): StickerStorage =
            instance ?: synchronized(this) {
                instance ?: StickerStorage(File(context.applicationContext.filesDir, "stickers")).also {
                    instance = it
                }
            }
    }
}
