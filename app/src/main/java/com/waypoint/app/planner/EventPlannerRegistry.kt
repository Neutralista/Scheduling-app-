package com.waypoint.app.planner

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
        _events.removeAll { it.category == EventCategory.SLEEP }
    }

    fun planToday(
        isWorkDay: Boolean = false,
        shiftStartMs: Long? = null,
        shiftEndMs: Long? = null
    ): DayPlan = planForDate(LocalDate.now(), isWorkDay, shiftStartMs, shiftEndMs)

    fun planForDate(
        date: LocalDate,
        isWorkDay: Boolean = false,
        shiftStartMs: Long? = null,
        shiftEndMs: Long? = null
    ): DayPlan {
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

        val dayStartMs = cal.timeInMillis
        val dayEndMs = dayStartMs + 24 * 3600_000L

        val freeBlocks = mutableListOf(TimeBlock(dayStartMs, dayEndMs))

        val eligible = mutableListOf<PlannerEvent>()
        val blocked = mutableListOf<BlockedEvent>()
        val scheduled = mutableListOf<ScheduledEvent>()

        for (event in _events) {
            if (event.fixedStartMillis != null && event.fixedEndMillis != null) {
                val start = maxOf(event.fixedStartMillis, dayStartMs)
                val end   = minOf(event.fixedEndMillis,   dayEndMs)
                if (end > start) scheduled += ScheduledEvent(event, start, end)
                continue
            }
            val reason = checkDayConditions(event, date, isWorkDay, shiftStartMs, shiftEndMs)
            if (reason != null) blocked += BlockedEvent(event, reason) else eligible += event
        }

        eligible.sortByDescending { it.priority }

        val fixedIntervals = scheduled.map { it.startMillis to it.endMillis }
        val remaining = freeBlocks.flatMap { block ->
            subtractIntervals(block.startMillis, block.endMillis, fixedIntervals)
        }.toMutableList()

        for (event in eligible) {
            val durationMs = event.durationMinutes * 60_000L
            val tw = event.conditions.filterIsInstance<EventCondition.TimeWindow>().firstOrNull()
            val beforeShift = event.conditions.any { it is EventCondition.BeforeShift }
            val afterShift  = event.conditions.any { it is EventCondition.AfterShift }
            val duringShift = event.conditions.any { it is EventCondition.DuringShift }
            var placed = false

            // DuringShift: place only within the shift window itself
            if (duringShift && shiftStartMs != null && shiftEndMs != null) {
                val shiftFitStart = if (tw != null) maxOf(shiftStartMs, toMs(tw.startHour, tw.startMin)) else shiftStartMs
                val shiftFitEnd   = if (tw != null) minOf(shiftEndMs,   toMs(tw.endHour,   tw.endMin))   else shiftEndMs
                if (shiftFitEnd - shiftFitStart >= durationMs) {
                    scheduled += ScheduledEvent(event, shiftFitStart, shiftFitStart + durationMs)
                    placed = true
                }
                if (!placed) blocked += BlockedEvent(event, "No available time in shift")
                continue
            }

            for (i in remaining.indices) {
                val (blockStart, blockEnd) = remaining[i]

                // Skip blocks that violate shift-relative placement
                if (beforeShift && shiftStartMs != null && blockStart >= shiftStartMs) continue
                if (afterShift  && shiftEndMs   != null && blockEnd   <= shiftEndMs)   continue

                val fitStart: Long
                val fitEnd: Long
                if (tw != null) {
                    fitStart = maxOf(blockStart, toMs(tw.startHour, tw.startMin))
                    fitEnd   = minOf(blockEnd,   toMs(tw.endHour,   tw.endMin))
                } else {
                    // For beforeShift, cap block end at shift start
                    val effectiveEnd = if (beforeShift && shiftStartMs != null) minOf(blockEnd, shiftStartMs) else blockEnd
                    // For afterShift, cap block start at shift end
                    val effectiveStart = if (afterShift && shiftEndMs != null) maxOf(blockStart, shiftEndMs) else blockStart
                    fitStart = effectiveStart; fitEnd = effectiveEnd
                }
                if (fitEnd - fitStart < durationMs) continue

                scheduled += ScheduledEvent(event, fitStart, fitStart + durationMs)
                remaining[i] = (fitStart + durationMs) to blockEnd
                placed = true
                break
            }

            if (!placed) blocked += BlockedEvent(event, "No available time slot")
        }

        // Urgent tasks above SLEEP priority may displace sleep windows.
        val urgentUnplaced = blocked.filter { b -> b.event.priority > PlannerPriority.SLEEP }
        if (urgentUnplaced.isNotEmpty()) {
            blocked.removeAll { b -> b.event.priority > PlannerPriority.SLEEP }
            for (entry in urgentUnplaced.sortedByDescending { it.event.priority }) {
                val event = entry.event
                val durationMs = event.durationMinutes * 60_000L
                val sleepSlots = scheduled
                    .filter { it.event.category == EventCategory.SLEEP }
                    .sortedBy { it.startMillis }
                var placed = false
                for (sleepSlot in sleepSlots) {
                    val available = sleepSlot.endMillis - sleepSlot.startMillis
                    if (available < durationMs) continue
                    val taskStart = sleepSlot.startMillis
                    val taskEnd   = taskStart + durationMs
                    scheduled.removeIf { it.event.id == sleepSlot.event.id }
                    scheduled += ScheduledEvent(event, taskStart, taskEnd)
                    val remainingSleep = sleepSlot.endMillis - taskEnd
                    if (remainingSleep >= 30 * 60_000L) {
                        scheduled += ScheduledEvent(sleepSlot.event, taskEnd, sleepSlot.endMillis)
                    }
                    placed = true
                    break
                }
                if (!placed) blocked += BlockedEvent(event, "No available time slot")
            }
        }

        return DayPlan(date, scheduled.sortedBy { it.startMillis }, blocked)
    }

    private fun subtractIntervals(
        start: Long, end: Long,
        subtract: List<Pair<Long, Long>>
    ): List<Pair<Long, Long>> {
        var segs = listOf(start to end)
        for ((iS, iE) in subtract) {
            segs = segs.flatMap { (s, e) ->
                when {
                    iE <= s || iS >= e -> listOf(s to e)
                    iS <= s && iE >= e -> emptyList()
                    iS <= s            -> listOf(iE to e)
                    iE >= e            -> listOf(s to iS)
                    else               -> listOf(s to iS, iE to e)
                }
            }
        }
        return segs.filter { (s, e) -> e > s }
    }

    private fun checkDayConditions(
        event: PlannerEvent,
        date: LocalDate,
        isWorkDay: Boolean,
        shiftStartMs: Long?,
        shiftEndMs: Long?
    ): String? {
        for (cond in event.conditions) when (cond) {
            is EventCondition.DaysOfWeek -> if (date.dayOfWeek.value !in cond.days) return "Not scheduled for today"
            is EventCondition.WorkDayOnly -> if (!isWorkDay) return "Work days only"
            is EventCondition.DayOffOnly  -> if (isWorkDay) return "Days off only"
            is EventCondition.BeforeShift,
            is EventCondition.DuringShift,
            is EventCondition.AfterShift -> if (!isWorkDay || shiftStartMs == null || shiftEndMs == null) return "No shift today"
            else -> Unit
        }
        return null
    }
}
