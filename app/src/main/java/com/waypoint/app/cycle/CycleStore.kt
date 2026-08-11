package com.waypoint.app.cycle

import android.content.Context
import com.waypoint.app.AppLogger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CycleStore(context: Context) {

    private val prefs = context.getSharedPreferences("waypoint_cycles", Context.MODE_PRIVATE)
    private val json  = Json { ignoreUnknownKeys = true }

    private companion object { const val TAG = "CycleStore" }

    fun save(cycle: Cycle) {
        prefs.edit().putString(cycle.id, json.encodeToString(cycle)).apply()
        AppLogger.i(TAG, "save: id=${cycle.id} date=${cycle.dateLabel} open=${cycle.isOpen}")
    }

    fun delete(id: String) {
        prefs.edit().remove(id).apply()
        AppLogger.i(TAG, "delete: id=$id")
    }

    /** All cycles, newest first. */
    fun loadAll(): List<Cycle> = prefs.all.values.mapNotNull { raw ->
        try { json.decodeFromString<Cycle>(raw as? String ?: return@mapNotNull null) }
        catch (_: Exception) { null }
    }.sortedByDescending { it.wakeMillis }

    /** The currently open cycle (should be at most one). */
    fun loadCurrent(): Cycle? = loadAll().firstOrNull { it.isOpen }

}
