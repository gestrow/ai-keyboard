// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StickerStorageTest {

    private lateinit var rootDir: File
    private lateinit var storage: StickerStorage

    @Before fun setUp() {
        rootDir = Files.createTempDirectory("sticker-test").toFile()
        storage = StickerStorage(rootDir)
    }

    @After fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun createPack_writesManifestAndCreatesPackDir() {
        val pack = storage.createPack("Test")
        val manifest = storage.getManifest()
        assertEquals(1, manifest.packs.size)
        assertEquals("Test", manifest.packs[0].name)
        assertTrue(File(rootDir, "manifest.json").exists())
        assertTrue(File(rootDir, "packs/${pack.id}").isDirectory)
    }

    @Test
    fun addSticker_isReflectedInManifest() {
        val pack = storage.createPack("Test")
        storage.addSticker(pack.id, "s1", "sticker_s1.webp")
        val updated = storage.getManifest().packs.first { it.id == pack.id }
        assertEquals(1, updated.stickers.size)
        assertEquals("s1", updated.stickers[0].id)
        assertEquals("sticker_s1.webp", updated.stickers[0].fileName)
    }

    @Test
    fun deleteSticker_removesFileAndManifestEntry() {
        val pack = storage.createPack("Test")
        // Simulate the file the normalizer would have written.
        val file = storage.stickerFile(pack.id, "sticker_s1.webp")
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        storage.addSticker(pack.id, "s1", "sticker_s1.webp")

        storage.deleteSticker(pack.id, "s1")

        assertFalse(file.exists())
        val updated = storage.getManifest().packs.first { it.id == pack.id }
        assertTrue(updated.stickers.isEmpty())
    }

    @Test
    fun deletePack_recursivelyRemovesPackDir() {
        val pack = storage.createPack("Test")
        val file = storage.stickerFile(pack.id, "sticker_s1.webp")
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        storage.addSticker(pack.id, "s1", "sticker_s1.webp")

        storage.deletePack(pack.id)

        assertFalse(File(rootDir, "packs/${pack.id}").exists())
        assertTrue(storage.getManifest().packs.isEmpty())
    }

    @Test
    fun getManifest_handlesCorruptManifestGracefully() {
        // Write a fresh storage instance so the cache is unset, then drop a corrupt file.
        val freshRoot = Files.createTempDirectory("sticker-corrupt").toFile()
        try {
            File(freshRoot, "manifest.json").apply {
                parentFile?.mkdirs()
                writeText("{ this is not valid json")
            }
            val fresh = StickerStorage(freshRoot)
            val manifest = fresh.getManifest()
            assertNotNull(manifest)
            assertTrue(manifest.packs.isEmpty())
            // Phase 3a precedent: corrupt file is preserved on failure paths.
            assertTrue(File(freshRoot, "manifest.json").exists())
        } finally {
            freshRoot.deleteRecursively()
        }
    }

    @Test
    fun ensureDefaultPack_createsOnEmpty_returnsExistingOtherwise() {
        assertTrue(storage.getManifest().packs.isEmpty())
        val first = storage.ensureDefaultPack("My Stickers")
        assertEquals("My Stickers", first.name)
        val second = storage.ensureDefaultPack("Other")
        assertEquals(first.id, second.id)
        assertEquals("My Stickers", second.name) // returned existing, not renamed
    }

    @Test
    fun renamePack_updatesNameWithoutTouchingStickers() {
        val pack = storage.createPack("Old")
        storage.addSticker(pack.id, "s1", "sticker_s1.webp")
        storage.renamePack(pack.id, "New")
        val renamed = storage.getManifest().packs.first { it.id == pack.id }
        assertEquals("New", renamed.name)
        assertEquals(1, renamed.stickers.size)
    }

    @Test
    fun deleteSticker_unknownIds_isNoOp() {
        val pack = storage.createPack("Test")
        storage.deleteSticker(pack.id, "ghost")
        storage.deleteSticker("ghost-pack", "ghost-sticker")
        assertEquals(1, storage.getManifest().packs.size)
        assertNull(storage.getManifest().packs.first().stickers.firstOrNull())
    }
}
