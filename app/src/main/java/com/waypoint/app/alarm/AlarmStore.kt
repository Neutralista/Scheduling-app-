package com.waypoint.app.alarm

import android.content.Context
import com.waypoint.app.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class AlarmStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json  = Json { ignoreUnknownKeys = true }

    private val _flow = MutableStateFlow(loadAll())
    val flow: StateFlow<List<AlarmEntry>> = _flow.asStateFlow()

    fun loadAll(): List<AlarmEntry> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return try { json.decodeFromString(raw) }
        catch (e: Exception) { AppLogger.e(TAG, "loadAll failed", e); emptyList() }
    }

    suspend fun add(alarm: AlarmEntry): AlarmEntry = withContext(Dispatchers.IO) {
        val entry = if (alarm.id.isBlank()) alarm.copy(id = UUID.randomUUID().toString()) else alarm
        val updated = loadAll().filter { it.id != entry.id } + entry
        persist(updated)
        entry
    }

    suspend fun update(alarm: AlarmEntry) = withContext(Dispatchers.IO) {
        persist(loadAll().map { if (it.id == alarm.id) alarm else it })
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        persist(loadAll().filter { it.id != id })
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        persist(loadAll().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    /** Synchronous write — safe to call from a BroadcastReceiver. */
    fun setEnabledSync(id: String, enabled: Boolean) {
        val updated = loadAll().map { if (it.id == id) it.copy(enabled = enabled) else it }
        prefs.edit().putString(KEY_LIST, json.encodeToString(updated)).commit()
        _flow.value = updated
    }

    private fun persist(list: List<AlarmEntry>) {
        prefs.edit().putString(KEY_LIST, json.encodeToString(list)).apply()
        _flow.value = list
    }

    companion object {
        private const val TAG       = "AlarmStore"
        private const val PREFS_NAME = "waypoint_user_alarms"
        private const val KEY_LIST   = "alarms"
    }
}
