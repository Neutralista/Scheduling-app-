package com.waypoint.app.cycle

import android.content.Context
import com.waypoint.app.AppLogger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CycleStore(context: Context) {

    private val prefs = context.getSharedPreferences("waypoint_cycles", Context.MODE_PRIVATE)
    private val json  = Json { ignoreUnknownKeys = true }

    private companion object { const val TAG = "CycleStore" }

    // Parsed history, rebuilt only after a write. loadCurrent() backs every task-completion
    // check, which otherwise re-parsed the whole, never-pruned cycle history each time. Safe
    // because this is the only CycleStore instance (owned by CycleTracker).
    private var cache: List<Cycle>? = null

    @Synchronized
    fun save(cycle: Cycle) {
        prefs.edit().putString(cycle.id, json.encodeToString(cycle)).apply()
        cache = null
        AppLogger.i(TAG, "save: id=${cycle.id} date=${cycle.dateLabel} open=${cycle.isOpen}")
    }

    @Synchronized
    fun delete(id: String) {
        prefs.edit().remove(id).apply()
        cache = null
        AppLogger.i(TAG, "delete: id=$id")
    }

    /** All cycles, newest first. */
    @Synchronized
    fun loadAll(): List<Cycle> = cache ?: prefs.all.values.mapNotNull { raw ->
        try { json.decodeFromString<Cycle>(raw as? String ?: return@mapNotNull null) }
        catch (_: Exception) { null }
    }.sortedByDescending { it.wakeMillis }.also { cache = it }

    /** The currently open cycle (should be at most one). */
    fun loadCurrent(): Cycle? = loadAll().firstOrNull { it.isOpen }

}
