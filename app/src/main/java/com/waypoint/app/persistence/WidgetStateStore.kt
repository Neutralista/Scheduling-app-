package com.waypoint.app.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.waypoint.app.widget.WidgetState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "widget_states")

/**
 * Persists WidgetState for all registered widgets via DataStore Preferences.
 *
 * Storage layout: all widget states are stored as a single JSON map under
 * one key ("all_states"). This keeps the implementation simple; revisit
 * only if the map grows large enough that partial reads matter.
 *
 * State survives process death and is restored on next launch.
 */
class WidgetStateStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val allStatesKey = stringPreferencesKey("all_states")

    fun allStates(): Flow<Map<String, WidgetState>> = context.dataStore.data.map { prefs ->
        prefs[allStatesKey]
            ?.runCatching { json.decodeFromString<Map<String, WidgetState>>(this) }
            ?.getOrNull()
            ?: emptyMap()
    }

    suspend fun save(widgetId: String, state: WidgetState) {
        context.dataStore.edit { prefs ->
            val current = prefs[allStatesKey]
                ?.runCatching { json.decodeFromString<Map<String, WidgetState>>(this) }
                ?.getOrNull()
                ?: emptyMap()
            prefs[allStatesKey] = json.encodeToString(current + (widgetId to state))
        }
    }

    suspend fun clear(widgetId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[allStatesKey]
                ?.runCatching { json.decodeFromString<Map<String, WidgetState>>(this) }
                ?.getOrNull()
                ?: emptyMap()
            prefs[allStatesKey] = json.encodeToString(current - widgetId)
        }
    }
}
