package com.waypoint.app.planner

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BlockSessionLog(
    val blockId: String,
    val blockName: String,
    val colorArgb: Int?,
    val date: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val tasksCompleted: Int,
    val tasksTotal: Int,
    val taskMeasurements: List<BlockTaskMeasurement> = emptyList(),
    /** How the block's phases actually went, in order; empty for a block without phases. */
    val phaseTimings: List<PhaseTiming> = emptyList()
)

@Serializable
data class PhaseTiming(val phaseId: String, val name: String, val startMs: Long, val endMs: Long) {
    val minutes: Int get() = ((endMs - startMs) / 60_000L).toInt()
}

class BlockSessionLogStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("wp_block_session_log", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun addEntry(log: BlockSessionLog) {
        val existing = loadAll().toMutableList()
        existing.add(0, log)
        val trimmed = existing.take(200)
        prefs.edit().putString("entries", json.encodeToString(trimmed)).apply()
    }

    /**
     * Adds [log] as that block's only session on its date. For an external app that reports the
     * whole day each time it syncs: appending made a second sync count the same sets twice.
     */
    fun replaceDay(log: BlockSessionLog) {
        val others = loadAll().filterNot { it.blockId == log.blockId && it.date == log.date }
        prefs.edit().putString("entries", json.encodeToString((listOf(log) + others).take(200))).apply()
    }

    fun loadAll(): List<BlockSessionLog> =
        prefs.getString("entries", null)?.let {
            try { json.decodeFromString<List<BlockSessionLog>>(it) } catch (_: Exception) { emptyList() }
        } ?: emptyList()

    /** Returns the historical average measured duration for a block task, or null if no data. */
    fun averageMeasuredMinutes(taskId: String): Int? =
        loadAll()
            .flatMap { it.taskMeasurements }
            .filter { it.taskId == taskId }
            .map { it.measuredMinutes }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toInt()

    fun deleteEntry(blockId: String, startedAtMs: Long) {
        val updated = loadAll().filter { !(it.blockId == blockId && it.startedAtMs == startedAtMs) }
        prefs.edit().putString("entries", json.encodeToString(updated)).apply()
    }

    fun updateEntry(log: BlockSessionLog) {
        val updated = loadAll().map {
            if (it.blockId == log.blockId && it.startedAtMs == log.startedAtMs) log else it
        }
        prefs.edit().putString("entries", json.encodeToString(updated)).apply()
    }

    fun clear() {
        prefs.edit().remove("entries").apply()
    }
}
