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

    fun planToday(ws: WorkScheduleSignals): DayPlan = planForDate(LocalDate.now(), ws)

    fun planForDate(date: LocalDate, ws: WorkScheduleSignals): DayPlan {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, date.year)
            set(Calendar.MONTH, date.monthValue - 1)
            set(Calendar.DAY_OF_MONTH, date.dayOfMonth)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
        }

        fun toMs(hour: Int, minute: Int): Long = (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0);         set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val schedule = ws.getSchedule(cal)
        val isWorkDay = schedule.isWork

        val dayStartMs = cal.timeInMillis
        // Extend planning window to 4 AM next day to allow cross-midnight events.
        // Must stay in sync with VIEW_START_HOUR in DayTimelineView.
        val dayEndMs = dayStartMs + 28 * 3600_000L

        val shiftStartMs = if (isWorkDay) schedule.shiftStart?.let { toMs(it.hour, it.minute) } else null
        val shiftEndMs = if (isWorkDay) schedule.shiftEnd?.let { t ->
            val ms = toMs(t.hour, t.minute)
            if (schedule.crossesMidnight) ms + 24 * 3600_000L else ms
        } else null

        val freeBlocks = mutableListOf<TimeBlock>()
        if (isWorkDay && shiftStartMs != null && shiftEndMs != null) {
            if (shiftStartMs > dayStartMs) freeBlocks += TimeBlock(dayStartMs, shiftStartMs)
            if (shiftEndMs < dayEndMs)     freeBlocks += TimeBlock(shiftEndMs, dayEndMs)
        } else {
            freeBlocks += TimeBlock(dayStartMs, dayEndMs)
        }

        val eligible = mutableListOf<PlannerEvent>()
        val blocked = mutableListOf<BlockedEvent>()
        for (event in _events) {
            val reason = checkDayConditions(event, date, isWorkDay)
            if (reason != null) blocked += BlockedEvent(event, reason) else eligible += event
        }

        eligible.sortByDescending { it.priority }

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
                    val twStart = toMs(tw.startHour, tw.startMin)
                    // If the window end is at or before the window start in same-day terms,
                    // it wraps past midnight — add 24h to get the next-day timestamp.
                    var twEnd = toMs(tw.endHour, tw.endMin)
                    if (twEnd <= twStart) twEnd += 24 * 3600_000L
                    fitStart = maxOf(blockStart, twStart)
                    fitEnd   = minOf(blockEnd,   twEnd)
                } else {
                    fitStart = blockStart; fitEnd = blockEnd
                }
                if (fitEnd - fitStart < durationMs) continue

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

        return DayPlan(date, scheduled.sortedBy { it.startMillis }, blocked)
    }

    private fun checkDayConditions(event: PlannerEvent, date: LocalDate, isWorkDay: Boolean): String? {
        for (cond in event.conditions) when (cond) {
            is EventCondition.WorkDayOnly -> if (!isWorkDay) return "Work days only"
            is EventCondition.DayOffOnly  -> if (isWorkDay)  return "Days off only"
            is EventCondition.DaysOfWeek  -> if (date.dayOfWeek.value !in cond.days) return "Not scheduled for today"
            else -> Unit
        }
        return null
    }
}
