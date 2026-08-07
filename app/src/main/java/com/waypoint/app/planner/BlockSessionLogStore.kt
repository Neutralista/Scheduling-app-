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
    val taskMeasurements: List<BlockTaskMeasurement> = emptyList()
)

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

    fun clear() {
        prefs.edit().remove("entries").apply()
    }
}
