package com.waypoint.app.planner

import android.content.Context
import com.waypoint.app.AppLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Parses "HH:MM" into (hour, minute); an unparsable or missing hour/minute component
 * falls back to [defaultHour]/[defaultMinute] individually. Returns null only when
 * [value] itself is null (i.e. that side of a window wasn't specified at all).
 */
internal fun parseClockTime(value: String?, defaultHour: Int, defaultMinute: Int): Pair<Int, Int>? {
    val parts = value?.split(":") ?: return null
    val hour = parts.getOrNull(0)?.toIntOrNull() ?: defaultHour
    val minute = parts.getOrNull(1)?.toIntOrNull() ?: defaultMinute
    return hour to minute
}

@Serializable
data class TaskConditionSpec(
    val type: String,
    val start: String? = null,
    val end: String? = null,
    val days: List<Int>? = null,
    val deadlineMillis: Long? = null,
    val referenceTaskIds: List<String>? = null,
    val calendarEventId: Long? = null,
    val blockId: String? = null,
    val intervalN: Int? = null,
    val anchorDate: String? = null,
    val oneOffDate: String? = null,
    val occurrenceCount: Int? = null,
    val flexMinutes: Int? = null
) {
    fun toEventCondition(): EventCondition? = when (type) {
        "timeWindow"     -> {
            val (sh, sm) = parseClockTime(start, 0, 0) ?: return null
            val (eh, em) = parseClockTime(end, 23, 59) ?: return null
            EventCondition.TimeWindow(sh, sm, eh, em)
        }
        "aroundTime"     -> {
            val (h, m) = parseClockTime(start, 12, 0) ?: return null
            EventCondition.AroundTime(h, m, flexMinutes ?: 60)
        }
        "daysOfWeek"     -> days?.let { EventCondition.DaysOfWeek(it.toSet()) }
        "workDayOnly"    -> EventCondition.WorkDayOnly
        "dayOffOnly"     -> EventCondition.DayOffOnly
        "beforeShift"    -> EventCondition.BeforeShift
        "duringShift"    -> EventCondition.DuringShift
        "afterShift"     -> EventCondition.AfterShift
        "deadline"       -> deadlineMillis?.let { EventCondition.Deadline(it) }
        "sameDayAs"      -> referenceTaskIds?.takeIf { it.isNotEmpty() }?.let { EventCondition.SameDayAs(it.toSet()) }
        "notSameDayAs"   -> referenceTaskIds?.takeIf { it.isNotEmpty() }?.let { EventCondition.NotSameDayAs(it.toSet()) }
        "beforeTask"     -> referenceTaskIds?.takeIf { it.isNotEmpty() }?.let { EventCondition.BeforeTask(it.toSet()) }
        "afterTask"      -> referenceTaskIds?.takeIf { it.isNotEmpty() }?.let { EventCondition.AfterTask(it.toSet()) }
        "beforeCalEvent" -> calendarEventId?.let { EventCondition.BeforeCalEvent(it) }
        "afterCalEvent"  -> calendarEventId?.let { EventCondition.AfterCalEvent(it) }
        "duringCalEvent" -> calendarEventId?.let { EventCondition.DuringCalEvent(it) }
        "beforeBlock"    -> blockId?.let { EventCondition.BeforeBlock(it) }
        "duringBlock"    -> blockId?.let { EventCondition.DuringBlock(it) }
        "afterBlock"     -> blockId?.let { EventCondition.AfterBlock(it) }
        "oneOff"         -> oneOffDate?.let { EventCondition.OneOff(it) }
        "everyNDays"     -> if (intervalN != null && anchorDate != null) EventCondition.EveryNDays(intervalN, anchorDate) else null
        "everyNWeeks"    -> if (intervalN != null && anchorDate != null) EventCondition.EveryNWeeks(intervalN, anchorDate) else null
        "everyNMonths"     -> if (intervalN != null && anchorDate != null) EventCondition.EveryNMonths(intervalN, anchorDate) else null
        "nTimesPerPeriod"  -> if (occurrenceCount != null && intervalN != null && anchorDate != null)
            EventCondition.NTimesPerPeriod(occurrenceCount, intervalN, anchorDate) else null
        else               -> null
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
    val triggers: List<TaskTrigger> = emptyList(),
    val scheduleLate: Boolean = false,
    val zone: PlannerZone? = null,
    val colorArgb: Int? = null
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
