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

    fun planToday(): DayPlan = planForDate(LocalDate.now())

    fun planForDate(date: LocalDate): DayPlan {
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
            val reason = checkDayConditions(event, date)
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
            var placed = false

            for (i in remaining.indices) {
                val (blockStart, blockEnd) = remaining[i]

                val fitStart: Long
                val fitEnd: Long
                if (tw != null) {
                    fitStart = maxOf(blockStart, toMs(tw.startHour, tw.startMin))
                    fitEnd   = minOf(blockEnd,   toMs(tw.endHour,   tw.endMin))
                } else {
                    fitStart = blockStart; fitEnd = blockEnd
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

    private fun checkDayConditions(event: PlannerEvent, date: LocalDate): String? {
        for (cond in event.conditions) when (cond) {
            is EventCondition.DaysOfWeek -> if (date.dayOfWeek.value !in cond.days) return "Not scheduled for today"
            else -> Unit
        }
        return null
    }
}
