package com.waypoint.app.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.waypoint.app.script.ScriptState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.scriptDataStore: DataStore<Preferences> by preferencesDataStore(name = "script_states")

class ScriptStateStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val allStatesKey = stringPreferencesKey("all_states")

    fun allStates(): Flow<Map<String, ScriptState>> = context.scriptDataStore.data.map { prefs ->
        prefs[allStatesKey]
            ?.runCatching { json.decodeFromString<Map<String, ScriptState>>(this) }
            ?.getOrNull()
            ?: emptyMap()
    }

    suspend fun save(scriptId: String, state: ScriptState) {
        context.scriptDataStore.edit { prefs ->
            val current = prefs[allStatesKey]
                ?.runCatching { json.decodeFromString<Map<String, ScriptState>>(this) }
                ?.getOrNull()
                ?: emptyMap()
            prefs[allStatesKey] = json.encodeToString(current + (scriptId to state))
        }
    }

    suspend fun clear(scriptId: String) {
        context.scriptDataStore.edit { prefs ->
            val current = prefs[allStatesKey]
                ?.runCatching { json.decodeFromString<Map<String, ScriptState>>(this) }
                ?.getOrNull()
                ?: emptyMap()
            prefs[allStatesKey] = json.encodeToString(current - scriptId)
        }
    }
}
