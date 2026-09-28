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
    val flexMinutes: Int? = null,
    /** What the tag referred to when it was made (a calendar event's title), for its label. */
    val label: String? = null
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
        // No work schedule exists to say which days are work days (the planner is always told
        // "not a work day"), so these made a block never schedule or always schedule. Ignored,
        // so blocks/tasks saved with them schedule every day.
        "workDayOnly", "dayOffOnly" -> null
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
    val colorArgb: Int? = null,
    /**
     * One-off tasks only: the date ("yyyy-MM-dd") it was done. Done marks reset every wake, so
     * this is what lets a done one-off be cleared from the queue afterwards instead of carrying
     * over as missed.
     */
    val completedOn: String? = null,
    /** Notify when the plan says this task starts (TaskReminderScheduler). */
    val remindAtStart: Boolean = false,
    /** "yyyy-MM-dd" to the start (epoch ms) it's pinned at that day, from dragging it on the timeline.
     *  A later time of the day's key carries its suffix ("yyyy-MM-dd~2"). */
    val pinnedStarts: Map<String, Long> = emptyMap(),
    /**
     * Several times a day: "HH:MM" for each, each its own to-do placed around that time. Empty (or
     * one) = once a day, placed by its time of day as usual.
     */
    val timesOfDay: List<String> = emptyList(),
    /** A one-off's exact start ("HH:MM") on its date: held there instead of planned. */
    val exactTime: String? = null,
    /**
     * Snoozed: "yyyy-MM-dd" (plus a later time of the day's suffix, "~2") to the moment (epoch ms)
     * it's not planned before that day. At or past the day's end = off that day.
     */
    val snoozedUntil: Map<String, Long> = emptyMap()
)

/** Separates a task's id from which of its times a day a planner entry is ("abc~2"). */
const val OCCURRENCE_SEP = "~"

/** The task a planner entry belongs to: its id without the time-of-day suffix. */
fun taskBaseId(eventId: String): String = eventId.substringBefore(OCCURRENCE_SEP)

/** The suffix a planner entry adds to its task's id ("~2"), or "" for the first time. */
fun occurrenceSuffix(eventId: String): String =
    eventId.substringAfter(OCCURRENCE_SEP, "").let { if (it.isEmpty()) "" else OCCURRENCE_SEP + it }

/** Whether it happens more than once a day. */
val TaskRequest.isMultiTime: Boolean get() = timesOfDay.size > 1

/**
 * Its planner entries' ids with their times: once a day, just its id (no time); several times,
 * its id for the first and "id~2", "id~3", … for the rest, so the first keeps the task's own id.
 */
fun TaskRequest.occurrences(): List<Pair<String, String?>> =
    if (!isMultiTime) listOf(id to null)
    else timesOfDay.mapIndexed { i, t -> (if (i == 0) id else "$id$OCCURRENCE_SEP${i + 1}") to t }

/** [n] times spread evenly from 08:00 to 20:00, on the half hour. */
fun defaultTimesOfDay(n: Int): List<String> = when {
    n <= 1 -> listOf("09:00")
    else -> (0 until n).map { i ->
        val minutes = 8 * 60 + ((12 * 60.0 * i / (n - 1)) / 30).toInt() * 30
        "%02d:%02d".format(minutes / 60, minutes % 60)
    }
}

/** The date of a one-off task's "oneOff" condition, or null if it isn't a one-off. */
fun List<TaskConditionSpec>.oneOffDate(): String? = firstOrNull { it.type == "oneOff" }?.oneOffDate

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
