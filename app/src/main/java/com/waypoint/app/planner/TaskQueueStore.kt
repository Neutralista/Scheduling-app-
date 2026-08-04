package com.waypoint.app.planner

import android.content.Context
import com.waypoint.app.AppLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TaskConditionSpec(
    val type: String,
    val start: String? = null,
    val end: String? = null,
    val days: List<Int>? = null,
    val deadlineMillis: Long? = null
) {
    fun toEventCondition(): EventCondition? = when (type) {
        "timeWindow"     -> {
            val s = start?.split(":") ?: return null
            val e = end?.split(":") ?: return null
            EventCondition.TimeWindow(
                s[0].toIntOrNull() ?: 0, s[1].toIntOrNull() ?: 0,
                e[0].toIntOrNull() ?: 0, e[1].toIntOrNull() ?: 0
            )
        }
        "daysOfWeek"     -> days?.let { EventCondition.DaysOfWeek(it.toSet()) }
        "workDayOnly"    -> EventCondition.WorkDayOnly
        "dayOffOnly"     -> EventCondition.DayOffOnly
        "beforeShift"    -> EventCondition.BeforeShift
        "duringShift"    -> EventCondition.DuringShift
        "afterShift"     -> EventCondition.AfterShift
        "deadline"       -> deadlineMillis?.let { EventCondition.Deadline(it) }
        else             -> null
    }
}

@Serializable
data class TaskRequest(
    val id: String,
    val title: String,
    val durationMinutes: Int,
    val priority: Int = 5,
    val sourceScriptId: String = "",
    val conditions: List<TaskConditionSpec> = emptyList(),
    val isRoutine: Boolean = false,
    val subtasks: List<SubtaskDef> = emptyList(),
    val bufferMinutes: Int = 0,
    val useMeasuredDuration: Boolean = false,
    val triggers: List<TaskTrigger> = emptyList()
)

class TaskQueueStore(context: Context) {
    private val prefs = context.getSharedPreferences("waypoint_task_queue", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val TAG = "TaskQueueStore"
    }

    fun submit(req: TaskRequest) {
        prefs.edit().putString(req.id, json.encodeToString(req)).apply()
        AppLogger.i(TAG, "submit: id=${req.id} title=${req.title} priority=${req.priority}")
    }

    fun retract(id: String) {
        prefs.edit().remove(id).apply()
        AppLogger.i(TAG, "retract: id=$id")
    }

    fun retractBySource(sourceScriptId: String) {
        val toRemove = loadAll().filter { it.sourceScriptId == sourceScriptId }.map { it.id }
        if (toRemove.isNotEmpty()) {
            prefs.edit().apply { toRemove.forEach { remove(it) } }.apply()
            AppLogger.i(TAG, "retractBySource: source=$sourceScriptId removed ${toRemove.size} tasks")
        }
    }

    fun loadAll(): List<TaskRequest> = prefs.all.values.mapNotNull { raw ->
        try { json.decodeFromString<TaskRequest>(raw as? String ?: return@mapNotNull null) }
        catch (_: Exception) { null }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
