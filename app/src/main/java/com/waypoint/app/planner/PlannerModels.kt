package com.waypoint.app.planner

import java.time.LocalDate

/** Sentinel task ID used in BeforeTask / AfterTask conditions to reference the sleep block. */
const val TASK_REF_SLEEP = "__SLEEP__"

/** Canonical priority levels for planner events. */
object PlannerPriority {
    const val URGENT  = 11  // above sleep; displaces sleep windows when no free time remains
    const val SLEEP   = 10  // sleep windows — high priority but displaceable by urgent tasks
}

/** Visual category — drives colour/rendering in the timeline, not scheduling. */
enum class EventCategory { DEFAULT, SLEEP, BUFFER }

data class PlannerEvent(
    val id: String,
    val title: String,
    val durationMinutes: Int,
    val priority: Int = 5,
    val conditions: List<EventCondition> = emptyList(),
    val sourceWidgetId: String? = null,
    val category: EventCategory = EventCategory.DEFAULT,
    /**
     * When both are set this is a one-off fixed event anchored to an absolute
     * time range. planForDate places it at the intersection with the planned day
     * (naturally splitting at midnight) and ignores day-condition checks.
     * durationMinutes is ignored — the range defines the length.
     */
    val fixedStartMillis: Long? = null,
    val fixedEndMillis: Long? = null,
    /** True for sleep events built from an actual log entry (past); false for computed planned windows. */
    val isLogged: Boolean = false,
    /** Extra minutes added after the event ends when computing free block consumption. */
    val bufferMinutes: Int = 0
)

sealed class EventCondition {
    /** Schedule only within this clock-time window today */
    data class TimeWindow(
        val startHour: Int, val startMin: Int,
        val endHour: Int, val endMin: Int
    ) : EventCondition()

    /** ISO day-of-week set: 1=Mon … 7=Sun */
    data class DaysOfWeek(val days: Set<Int>) : EventCondition()

    /** Only schedule on work days (requires work schedule context) */
    object WorkDayOnly : EventCondition()

    /** Only schedule on days off (requires work schedule context) */
    object DayOffOnly : EventCondition()

    /** Only schedule in the free blocks before the shift starts */
    object BeforeShift : EventCondition()

    /** Only schedule within the shift window */
    object DuringShift : EventCondition()

    /** Only schedule in the free blocks after the shift ends */
    object AfterShift : EventCondition()

    /** Must be placed before this absolute deadline (epoch ms) */
    data class Deadline(val byMillis: Long) : EventCondition()

    /** Only schedule on days when ALL of the referenced tasks are also scheduled */
    data class SameDayAs(val taskIds: Set<String>) : EventCondition()

    /** Only schedule on days when NONE of the referenced tasks are scheduled */
    data class NotSameDayAs(val taskIds: Set<String>) : EventCondition()

    /** Must be placed before the earliest scheduled start of any referenced task */
    data class BeforeTask(val taskIds: Set<String>) : EventCondition()

    /** Must be placed after the latest scheduled end of any referenced task */
    data class AfterTask(val taskIds: Set<String>) : EventCondition()

    /** Must finish before the start of a specific calendar event */
    data class BeforeCalEvent(val eventId: Long) : EventCondition()

    /** Must start after the end of a specific calendar event */
    data class AfterCalEvent(val eventId: Long) : EventCondition()

    /** Must be placed within the reserved time slot of a specific calendar event */
    data class DuringCalEvent(val eventId: Long) : EventCondition()
}

data class ScheduledEvent(
    val event: PlannerEvent,
    val startMillis: Long,
    val endMillis: Long
)

data class BlockedEvent(val event: PlannerEvent, val reason: String)

data class TimeBlock(val startMillis: Long, val endMillis: Long) {
    val durationMinutes: Int get() = ((endMillis - startMillis) / 60_000L).toInt()
}

data class DayPlan(
    val date: LocalDate,
    val scheduled: List<ScheduledEvent>,
    val blocked: List<BlockedEvent>
)
