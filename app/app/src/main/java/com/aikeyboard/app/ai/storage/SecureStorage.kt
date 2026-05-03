// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aikeyboard.app.ai.client.Provider
import com.aikeyboard.app.ai.persona.DefaultPersonas
import com.aikeyboard.app.ai.persona.Persona
import com.aikeyboard.app.latin.utils.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-disk encrypted blob ({@link #SECURE_FILE}) holding personas, the active persona id,
 * and configured provider API keys. The blob is sealed with an AES-256-GCM AEAD whose
 * keyset is wrapped by the Android Keystore master key {@link #MASTER_KEY_URI}.
 *
 * Migrates Phase 2's {@link EncryptedSharedPreferences} blob ({@code ai_keyboard_secure.prefs.xml})
 * once on first launch, preserving personas. The legacy file is only deleted after the new
 * blob is written successfully; on migration failure the old file is left in place so a
 * future launch can retry.
 */
class SecureStorage private constructor(private val appContext: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val aead: Aead by lazy {
        AeadConfig.register()
        val handle: KeysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private val secureFile: File
        get() = File(appContext.filesDir, SECURE_FILE)

    @Volatile private var cache: SecureData? = null

    init {
        migrateFromEncryptedSharedPreferencesIfNeeded()
    }

    @Synchronized
    fun getPersonas(): List<Persona> {
        val data = load()
        if (data.personas.isNotEmpty()) return data.personas
        // First-read on a fresh install: seed defaults and persist.
        val seeded = DefaultPersonas.all
        save(data.copy(personas = seeded))
        return seeded
    }

    @Synchronized
    fun savePersona(persona: Persona) {
        val current = getPersonas().toMutableList()
        val idx = current.indexOfFirst { it.id == persona.id }
        if (idx >= 0) current[idx] = persona else current.add(persona)
        save(load().copy(personas = current))
    }

    @Synchronized
    fun deletePersona(id: String) {
        val current = getPersonas()
        val target = current.firstOrNull { it.id == id } ?: return
        require(!target.isBuiltIn) { "Built-in personas cannot be deleted" }
        val data = load()
        val updated = data.copy(
            personas = current.filterNot { it.id == id },
            activePersonaId = if (data.activePersonaId == id) DefaultPersonas.DEFAULT_ID else data.activePersonaId,
        )
        save(updated)
    }

    @Synchronized
    fun getActivePersonaId(): String {
        getPersonas() // ensure seeded
        return load().activePersonaId ?: DefaultPersonas.DEFAULT_ID
    }

    @Synchronized
    fun setActivePersonaId(id: String) {
        save(load().copy(activePersonaId = id))
    }

    @Synchronized
    fun getApiKey(provider: Provider): String? =
        load().apiKeys[provider.storageKey]?.takeIf { it.isNotEmpty() }

    @Synchronized
    fun saveApiKey(provider: Provider, key: String) {
        val data = load()
        val updated = data.apiKeys.toMutableMap().apply { put(provider.storageKey, key) }
        save(data.copy(apiKeys = updated))
    }

    @Synchronized
    fun deleteApiKey(provider: Provider) {
        val data = load()
        if (!data.apiKeys.containsKey(provider.storageKey)) return
        val updated = data.apiKeys.toMutableMap().apply { remove(provider.storageKey) }
        save(data.copy(apiKeys = updated))
    }

    @Synchronized
    fun getConfiguredProviders(): Set<Provider> =
        load().apiKeys.keys
            .mapNotNull { Provider.fromStorageKey(it) }
            .toSet()

    private fun load(): SecureData {
        cache?.let { return it }
        val file = secureFile
        if (!file.exists()) {
            val empty = SecureData()
            cache = empty
            return empty
        }
        return runCatching {
            val ciphertext = file.readBytes()
            val plaintext = aead.decrypt(ciphertext, AAD)
            json.decodeFromString(SecureData.serializer(), String(plaintext, Charsets.UTF_8))
        }.getOrElse {
            Log.e(TAG, "Failed to read SecureStorage; resetting", it)
            SecureData()
        }.also { cache = it }
    }

    private fun save(data: SecureData) {
        val plaintext = json.encodeToString(SecureData.serializer(), data).toByteArray(Charsets.UTF_8)
        val ciphertext = aead.encrypt(plaintext, AAD)
        val tmp = File(appContext.filesDir, "$SECURE_FILE.tmp")
        tmp.writeBytes(ciphertext)
        if (!tmp.renameTo(secureFile)) {
            // On API levels where atomic rename can fail, fall back to overwrite.
            secureFile.writeBytes(ciphertext)
            tmp.delete()
        }
        cache = data
    }

    @Suppress("DEPRECATION")
    private fun migrateFromEncryptedSharedPreferencesIfNeeded() {
        val oldPrefsFile = File(appContext.dataDir, "shared_prefs/$LEGACY_PREFS_FILE.xml")
        if (!oldPrefsFile.exists()) return
        if (secureFile.exists()) {
            // Already migrated on a previous launch; clean up any lingering legacy file.
            oldPrefsFile.delete()
            return
        }
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val oldPrefs = EncryptedSharedPreferences.create(
                appContext,
                LEGACY_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            val personasRaw = oldPrefs.getString(LEGACY_KEY_PERSONAS, null)
            val activeId = oldPrefs.getString(LEGACY_KEY_ACTIVE_PERSONA_ID, null)
            val personas: List<Persona> = if (!personasRaw.isNullOrEmpty()) {
                runCatching {
                    json.decodeFromString(ListSerializer(Persona.serializer()), personasRaw)
                }.getOrElse { emptyList() }
            } else emptyList()

            // Write new file FIRST, only delete old file after success.
            save(SecureData(personas = personas, activePersonaId = activeId))
            oldPrefsFile.delete()
            Log.i(TAG, "Migrated SecureStorage from EncryptedSharedPreferences (${personas.size} personas)")
        } catch (e: Exception) {
            // Don't drop user data: preserve old file so a future launch can retry.
            Log.e(TAG, "SecureStorage migration failed; legacy file preserved", e)
        }
    }

    companion object {
        private const val TAG = "SecureStorage"

        private const val SECURE_FILE = "ai_keyboard_secure.bin"
        private const val KEYSET_NAME = "ai_keyboard_secure_keyset"
        private const val KEYSET_PREFS = "ai_keyboard_keyset_prefs"
        private const val MASTER_KEY_URI = "android-keystore://ai_keyboard_master_key"

        private const val LEGACY_PREFS_FILE = "ai_keyboard_secure.prefs"
        private const val LEGACY_KEY_PERSONAS = "personas_json"
        private const val LEGACY_KEY_ACTIVE_PERSONA_ID = "active_persona_id"

        // AAD binds ciphertext to this app's intent for the data (defense in depth).
        private val AAD = "ai_keyboard_secure_v1".toByteArray(Charsets.UTF_8)

        @Volatile private var instance: SecureStorage? = null

        fun getInstance(context: Context): SecureStorage =
            instance ?: synchronized(this) {
                instance ?: SecureStorage(context.applicationContext).also { instance = it }
            }
    }
}
