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

        val eligible = mutableListOf<PlannerEvent>()
        val dependentEligible = mutableListOf<PlannerEvent>()
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
            if (reason != null) { blocked += BlockedEvent(event, reason); continue }
            val hasTaskRelative = event.conditions.any {
                it is EventCondition.SameDayAs || it is EventCondition.NotSameDayAs ||
                it is EventCondition.BeforeTask || it is EventCondition.AfterTask
            }
            if (hasTaskRelative) dependentEligible += event else eligible += event
        }

        eligible.sortByDescending { it.priority }

        // Free time starts when the user wakes (end of the sleep block), not at midnight.
        // This aligns scheduling with the waking cycle rather than the calendar boundary.
        val cycleStartMs = scheduled
            .filter { it.event.category == EventCategory.SLEEP && it.endMillis > dayStartMs }
            .maxOfOrNull { it.endMillis } ?: dayStartMs

        val freeBlocks = mutableListOf(TimeBlock(cycleStartMs, dayEndMs))
        val fixedIntervals = scheduled.map { it.startMillis to it.endMillis }
        val remaining = freeBlocks.flatMap { block ->
            subtractIntervals(block.startMillis, block.endMillis, fixedIntervals)
        }.toMutableList()

        // ── Pass 1: independent events (no task-relative conditions) ─────────
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

                if (beforeShift && shiftStartMs != null && blockStart >= shiftStartMs) continue
                if (afterShift  && shiftEndMs   != null && blockEnd   <= shiftEndMs)   continue

                val fitStart: Long
                val fitEnd: Long
                if (tw != null) {
                    fitStart = maxOf(blockStart, toMs(tw.startHour, tw.startMin))
                    fitEnd   = minOf(blockEnd,   toMs(tw.endHour,   tw.endMin))
                } else {
                    val effectiveEnd   = if (beforeShift && shiftStartMs != null) minOf(blockEnd,   shiftStartMs) else blockEnd
                    val effectiveStart = if (afterShift  && shiftEndMs   != null) maxOf(blockStart, shiftEndMs)   else blockStart
                    fitStart = effectiveStart; fitEnd = effectiveEnd
                }
                val deadline = event.conditions.filterIsInstance<EventCondition.Deadline>().firstOrNull()
                if (deadline != null && fitStart + durationMs > deadline.byMillis) continue
                if (fitEnd - fitStart < durationMs) continue

                scheduled += ScheduledEvent(event, fitStart, fitStart + durationMs)
                val bufferMs = event.bufferMinutes * 60_000L
                remaining[i] = (fitStart + durationMs + bufferMs) to blockEnd
                placed = true
                break
            }

            if (!placed) blocked += BlockedEvent(event, "No available time slot")
        }

        // ── Pass 2: task-relative events (evaluated against Pass 1 results) ──
        for (event in dependentEligible.sortedByDescending { it.priority }) {
            val sameDayAs    = event.conditions.filterIsInstance<EventCondition.SameDayAs>().firstOrNull()
            val notSameDayAs = event.conditions.filterIsInstance<EventCondition.NotSameDayAs>().firstOrNull()
            val beforeTask   = event.conditions.filterIsInstance<EventCondition.BeforeTask>().firstOrNull()
            val afterTask    = event.conditions.filterIsInstance<EventCondition.AfterTask>().firstOrNull()

            val ids = scheduled.map { it.event.id }.toSet()
            if (sameDayAs != null && !sameDayAs.taskIds.all { it in ids }) {
                blocked += BlockedEvent(event, "Required tasks not scheduled today"); continue
            }
            if (notSameDayAs != null && notSameDayAs.taskIds.any { it in ids }) {
                blocked += BlockedEvent(event, "Excluded tasks are scheduled today"); continue
            }

            // Resolve sleep anchor bounds from the scheduled sleep block(s)
            val sleepStartMs = scheduled.filter { it.event.category == EventCategory.SLEEP }
                .minOfOrNull { it.startMillis }
            val sleepEndMs = scheduled.filter { it.event.category == EventCategory.SLEEP }
                .maxOfOrNull { it.endMillis }

            // Derive hard time bounds from referenced scheduled tasks (or sleep sentinel)
            val mustEndBefore = beforeTask?.taskIds?.mapNotNull { id ->
                if (id == TASK_REF_SLEEP) sleepStartMs
                else scheduled.find { it.event.id == id }?.startMillis
            }?.minOrNull()
            val mustStartAfter = afterTask?.taskIds?.mapNotNull { id ->
                if (id == TASK_REF_SLEEP) sleepEndMs
                else scheduled.find { it.event.id == id }?.endMillis
            }?.maxOrNull()

            val durationMs = event.durationMinutes * 60_000L
            val tw = event.conditions.filterIsInstance<EventCondition.TimeWindow>().firstOrNull()
            var placed = false

            for (i in remaining.indices) {
                val (blockStart, blockEnd) = remaining[i]
                val fitStart = maxOf(
                    blockStart,
                    mustStartAfter ?: blockStart,
                    tw?.let { toMs(it.startHour, it.startMin) } ?: blockStart
                )
                val fitEnd = minOf(
                    blockEnd,
                    mustEndBefore ?: blockEnd,
                    tw?.let { toMs(it.endHour, it.endMin) } ?: blockEnd
                )
                val deadline = event.conditions.filterIsInstance<EventCondition.Deadline>().firstOrNull()
                if (deadline != null && fitStart + durationMs > deadline.byMillis) continue
                if (fitEnd - fitStart < durationMs) continue

                scheduled += ScheduledEvent(event, fitStart, fitStart + durationMs)
                val bufferMs = event.bufferMinutes * 60_000L
                remaining[i] = (fitStart + durationMs + bufferMs) to blockEnd
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
            is EventCondition.Deadline -> if (System.currentTimeMillis() > cond.byMillis) return "Past deadline"
            else -> Unit
        }
        return null
    }
}
