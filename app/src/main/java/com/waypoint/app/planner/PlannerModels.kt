package com.waypoint.app.planner

import java.time.LocalDate

/** Canonical priority levels for planner events. */
object PlannerPriority {
    const val SHIFT = 99  // work shifts — immovable backbone; conceptual only
    const val SLEEP = 10  // sleep windows — high priority but user-adjustable
}

/** Visual category — drives colour/rendering in the timeline, not scheduling. */
enum class EventCategory { DEFAULT, SLEEP }

data class PlannerEvent(
    val id: String,
    val title: String,
    val durationMinutes: Int,
    val priority: Int = 5,
    val conditions: List<EventCondition> = emptyList(),
    val sourceWidgetId: String? = null,
    val category: EventCategory = EventCategory.DEFAULT
)

sealed class EventCondition {
    /** Schedule only within this clock-time window today */
    data class TimeWindow(
        val startHour: Int, val startMin: Int,
        val endHour: Int, val endMin: Int
    ) : EventCondition()

    object WorkDayOnly : EventCondition()
    object DayOffOnly : EventCondition()

    /** Cannot overlap the active shift window */
    object NotDuringShift : EventCondition()

    /** ISO day-of-week set: 1=Mon … 7=Sun */
    data class DaysOfWeek(val days: Set<Int>) : EventCondition()
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
