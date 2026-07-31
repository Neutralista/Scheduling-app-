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
        AppLogger.i(TAG, "loadAll: reading prefs")
        val raw = prefs.getString(KEY_LIST, null)
        if (raw == null) {
            AppLogger.i(TAG, "loadAll: no data stored, returning empty list")
            return emptyList()
        }
        return try {
            val result = json.decodeFromString<List<AlarmEntry>>(raw)
            AppLogger.i(TAG, "loadAll: decoded ${result.size} alarm(s): ${result.map { "${it.id.take(8)}@${it.displayTime}" }}")
            result
        } catch (e: Throwable) {
            AppLogger.e(TAG, "loadAll: decode failed — raw=$raw", e)
            emptyList()
        }
    }

    suspend fun add(alarm: AlarmEntry): AlarmEntry = withContext(Dispatchers.IO) {
        val entry = if (alarm.id.isBlank()) alarm.copy(id = UUID.randomUUID().toString()) else alarm
        AppLogger.i(TAG, "add: id=${entry.id.take(8)} time=${entry.displayTime} repeat=${entry.repeatDays} enabled=${entry.enabled}")
        val current = loadAll()
        val updated = current.filter { it.id != entry.id } + entry
        AppLogger.i(TAG, "add: list size ${current.size} → ${updated.size}")
        persist(updated)
        entry
    }

    suspend fun update(alarm: AlarmEntry) = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "update: id=${alarm.id.take(8)} time=${alarm.displayTime} enabled=${alarm.enabled}")
        val current = loadAll()
        val updated = current.map { if (it.id == alarm.id) alarm else it }
        val changed = current.count { it.id == alarm.id }
        AppLogger.i(TAG, "update: matched $changed entry(ies) in list of ${current.size}")
        persist(updated)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "delete: id=${id.take(8)}")
        val current = loadAll()
        val updated = current.filter { it.id != id }
        AppLogger.i(TAG, "delete: list size ${current.size} → ${updated.size}")
        persist(updated)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "setEnabled: id=${id.take(8)} enabled=$enabled")
        val current = loadAll()
        val updated = current.map { if (it.id == id) it.copy(enabled = enabled) else it }
        AppLogger.i(TAG, "setEnabled: matched ${current.count { it.id == id }} entry(ies)")
        persist(updated)
    }

    /** Synchronous write — safe to call from a BroadcastReceiver. */
    fun setEnabledSync(id: String, enabled: Boolean) {
        AppLogger.i(TAG, "setEnabledSync: id=${id.take(8)} enabled=$enabled")
        val current = loadAll()
        val updated = current.map { if (it.id == id) it.copy(enabled = enabled) else it }
        AppLogger.i(TAG, "setEnabledSync: committing to prefs (${updated.size} alarm(s))")
        prefs.edit().putString(KEY_LIST, json.encodeToString(updated)).commit()
        _flow.value = updated
        AppLogger.i(TAG, "setEnabledSync: done")
    }

    private fun persist(list: List<AlarmEntry>) {
        AppLogger.i(TAG, "persist: writing ${list.size} alarm(s) to prefs")
        prefs.edit().putString(KEY_LIST, json.encodeToString(list)).apply()
        _flow.value = list
        AppLogger.i(TAG, "persist: done")
    }

    companion object {
        private const val TAG       = "AlarmStore"
        private const val PREFS_NAME = "waypoint_user_alarms"
        private const val KEY_LIST   = "alarms"
    }
}
