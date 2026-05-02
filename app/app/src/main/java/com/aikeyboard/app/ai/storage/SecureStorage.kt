// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aikeyboard.app.ai.persona.DefaultPersonas
import com.aikeyboard.app.ai.persona.Persona
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class SecureStorage private constructor(appContext: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val personaListSerializer = ListSerializer(Persona.serializer())

    @Synchronized
    fun getPersonas(): List<Persona> {
        val raw = prefs.getString(KEY_PERSONAS, null)
        if (raw.isNullOrEmpty()) {
            val seeded = DefaultPersonas.all
            persistPersonas(seeded)
            return seeded
        }
        return runCatching { json.decodeFromString(personaListSerializer, raw) }
            .getOrElse {
                val seeded = DefaultPersonas.all
                persistPersonas(seeded)
                seeded
            }
    }

    @Synchronized
    fun savePersona(persona: Persona) {
        val current = getPersonas().toMutableList()
        val idx = current.indexOfFirst { it.id == persona.id }
        if (idx >= 0) current[idx] = persona else current.add(persona)
        persistPersonas(current)
    }

    @Synchronized
    fun deletePersona(id: String) {
        val current = getPersonas()
        val target = current.firstOrNull { it.id == id } ?: return
        require(!target.isBuiltIn) { "Built-in personas cannot be deleted" }
        persistPersonas(current.filterNot { it.id == id })
        if (getActivePersonaIdInternal() == id) {
            prefs.edit { putString(KEY_ACTIVE_PERSONA_ID, DefaultPersonas.DEFAULT_ID) }
        }
    }

    @Synchronized
    fun getActivePersonaId(): String {
        getPersonas() // ensure seeded
        return getActivePersonaIdInternal()
    }

    @Synchronized
    fun setActivePersonaId(id: String) {
        prefs.edit { putString(KEY_ACTIVE_PERSONA_ID, id) }
    }

    private fun getActivePersonaIdInternal(): String =
        prefs.getString(KEY_ACTIVE_PERSONA_ID, DefaultPersonas.DEFAULT_ID) ?: DefaultPersonas.DEFAULT_ID

    private fun persistPersonas(list: List<Persona>) {
        val encoded = json.encodeToString(personaListSerializer, list)
        prefs.edit { putString(KEY_PERSONAS, encoded) }
    }

    companion object {
        private const val PREFS_FILE = "ai_keyboard_secure.prefs"
        private const val KEY_PERSONAS = "personas_json"
        private const val KEY_ACTIVE_PERSONA_ID = "active_persona_id"

        @Volatile private var instance: SecureStorage? = null

        fun getInstance(context: Context): SecureStorage =
            instance ?: synchronized(this) {
                instance ?: SecureStorage(context.applicationContext).also { instance = it }
            }
    }
}
