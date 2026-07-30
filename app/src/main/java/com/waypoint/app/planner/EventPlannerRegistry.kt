package com.waypoint.app.planner

import com.waypoint.app.signal.WorkScheduleSignals
import java.time.LocalDate
import java.util.Calendar

class EventPlannerRegistry {

    private val _events = mutableListOf<PlannerEvent>()
    val events: List<PlannerEvent> get() = _events.toList()

    fun register(event: PlannerEvent) {
        _events.removeAll { it.id == event.id }
        _events.add(event)
    }

    fun unregister(eventId: String) {
        _events.removeAll { it.id == eventId }
    }

    fun unregisterByWidget(widgetId: String) {
        _events.removeAll { it.sourceWidgetId == widgetId }
    }

    fun clearSleepEvents() {
        _events.removeAll { it.id == "sleep_morning" || it.id == "sleep_evening" }
    }

    fun planToday(ws: WorkScheduleSignals): DayPlan {
        val today = LocalDate.now()
        val todaySchedule = ws.getTodaySchedule()
        val isWorkDay = todaySchedule.isWork

        val dayStartMs = startOfDayMillis()
        val dayEndMs = dayStartMs + 24 * 3600_000L

        val shiftStartMs = if (isWorkDay) todaySchedule.shiftStart?.let { toMillisToday(it.hour, it.minute) } else null
        val shiftEndMs = if (isWorkDay) todaySchedule.shiftEnd?.let { t ->
            val ms = toMillisToday(t.hour, t.minute)
            if (todaySchedule.crossesMidnight) ms + 24 * 3600_000L else ms
        } else null

        // Free blocks are time outside the shift; on a day off the whole day is free
        val freeBlocks = mutableListOf<TimeBlock>()
        if (isWorkDay && shiftStartMs != null && shiftEndMs != null) {
            if (shiftStartMs > dayStartMs) freeBlocks += TimeBlock(dayStartMs, shiftStartMs)
            if (shiftEndMs < dayEndMs)     freeBlocks += TimeBlock(shiftEndMs, dayEndMs)
        } else {
            freeBlocks += TimeBlock(dayStartMs, dayEndMs)
        }

        // Day-level condition pass (time-window and NotDuringShift are resolved during placement)
        val eligible = mutableListOf<PlannerEvent>()
        val blocked = mutableListOf<BlockedEvent>()
        for (event in _events) {
            val reason = checkDayConditions(event, today, isWorkDay)
            if (reason != null) blocked += BlockedEvent(event, reason) else eligible += event
        }

        eligible.sortByDescending { it.priority }

        // Greedy placement
        val remaining = freeBlocks.map { it.startMillis to it.endMillis }.toMutableList()
        val scheduled = mutableListOf<ScheduledEvent>()

        for (event in eligible) {
            val durationMs = event.durationMinutes * 60_000L
            val notDuringShift = event.conditions.any { it is EventCondition.NotDuringShift }
            val tw = event.conditions.filterIsInstance<EventCondition.TimeWindow>().firstOrNull()
            var placed = false

            for (i in remaining.indices) {
                val (blockStart, blockEnd) = remaining[i]

                val fitStart: Long
                val fitEnd: Long
                if (tw != null) {
                    fitStart = maxOf(blockStart, toMillisToday(tw.startHour, tw.startMin))
                    fitEnd   = minOf(blockEnd,   toMillisToday(tw.endHour,   tw.endMin))
                } else {
                    fitStart = blockStart; fitEnd = blockEnd
                }
                if (fitEnd - fitStart < durationMs) continue

                // Skip blocks that overlap the shift when NotDuringShift is set
                if (notDuringShift && shiftStartMs != null && shiftEndMs != null) {
                    if (fitStart < shiftEndMs && fitEnd > shiftStartMs) continue
                }

                scheduled += ScheduledEvent(event, fitStart, fitStart + durationMs)
                remaining[i] = (fitStart + durationMs) to blockEnd
                placed = true
                break
            }

            if (!placed) blocked += BlockedEvent(event, "No available time slot")
        }

        return DayPlan(today, scheduled.sortedBy { it.startMillis }, blocked)
    }

    private fun checkDayConditions(event: PlannerEvent, today: LocalDate, isWorkDay: Boolean): String? {
        for (cond in event.conditions) when (cond) {
            is EventCondition.WorkDayOnly -> if (!isWorkDay) return "Work days only"
            is EventCondition.DayOffOnly  -> if (isWorkDay)  return "Days off only"
            is EventCondition.DaysOfWeek  -> if (today.dayOfWeek.value !in cond.days) return "Not scheduled for today"
            else -> Unit
        }
        return null
    }

    private fun startOfDayMillis() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun toMillisToday(hour: Int, minute: Int) = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0);         set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
