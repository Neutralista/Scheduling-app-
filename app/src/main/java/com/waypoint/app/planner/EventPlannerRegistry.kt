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
        _events.removeAll { it.category == EventCategory.SLEEP }
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
        val dayEndMs = dayStartMs + 24 * 3600_000L

        val shiftStartMs = if (isWorkDay) schedule.shiftStart?.let { toMs(it.hour, it.minute) } else null
        val shiftEndMs = if (isWorkDay) schedule.shiftEnd?.let { t ->
            val ms = toMs(t.hour, t.minute)
            if (schedule.crossesMidnight) ms + 24 * 3600_000L else ms
        } else null

        // For night shifts that end at or past midnight, extend the planning window so
        // AfterShift tasks have somewhere to land (up to 6 h past the shift end).
        val effectiveEndMs = if (shiftEndMs != null && shiftEndMs >= dayEndMs)
            shiftEndMs + 6 * 3600_000L else dayEndMs

        val freeBlocks = mutableListOf<TimeBlock>()
        if (isWorkDay && shiftStartMs != null && shiftEndMs != null) {
            if (shiftStartMs > dayStartMs) freeBlocks += TimeBlock(dayStartMs, shiftStartMs)
            if (shiftEndMs < effectiveEndMs) freeBlocks += TimeBlock(shiftEndMs, effectiveEndMs)
        } else {
            freeBlocks += TimeBlock(dayStartMs, dayEndMs)
        }

        val eligible = mutableListOf<PlannerEvent>()
        val blocked = mutableListOf<BlockedEvent>()
        val scheduled = mutableListOf<ScheduledEvent>()

        for (event in _events) {
            // Fixed one-off events: place at intersection with this day, skip day conditions.
            if (event.fixedStartMillis != null && event.fixedEndMillis != null) {
                val start = maxOf(event.fixedStartMillis, dayStartMs)
                val end   = minOf(event.fixedEndMillis,   dayEndMs)
                if (end > start) scheduled += ScheduledEvent(event, start, end)
                continue
            }
            val reason = checkDayConditions(event, date, isWorkDay)
            if (reason != null) blocked += BlockedEvent(event, reason) else eligible += event
        }

        eligible.sortByDescending { it.priority }

        // Three placement pools: during-shift, after-shift (dedicated sleep-push pass),
        // and regular (greedy free-block fill).
        val (duringShiftEligible, nonDuringEligible) = eligible.partition { e ->
            e.conditions.any { it is EventCondition.DuringShift }
        }
        val (afterShiftEligible, regularEligible) = nonDuringEligible.partition { e ->
            e.conditions.any { it is EventCondition.AfterShift }
        }

        // Build the available pool, subtracting fixed events (e.g. sleep) so regular tasks
        // are never placed overlapping them.
        val fixedIntervals = scheduled.map { it.startMillis to it.endMillis }
        val remaining = freeBlocks.flatMap { block ->
            subtractIntervals(block.startMillis, block.endMillis, fixedIntervals)
        }.toMutableList()

        // ── AfterShift placement ─────────────────────────────────────────────────
        // Place AfterShift events right at shift end in priority order.
        // Any sleep event that sits in the way is pushed later to make room.
        if (shiftEndMs != null) {
            var cursor = shiftEndMs
            for (event in afterShiftEligible.sortedByDescending { it.priority }) {
                val durationMs = event.durationMinutes * 60_000L
                val tw = event.conditions.filterIsInstance<EventCondition.TimeWindow>().firstOrNull()
                val fitStart = if (tw != null) maxOf(cursor, toMs(tw.startHour, tw.startMin)) else cursor
                val fitEnd   = if (tw != null) toMs(tw.endHour, tw.endMin) else effectiveEndMs
                if (fitEnd - fitStart < durationMs) {
                    blocked += BlockedEvent(event, "No available time after shift")
                    continue
                }
                val taskStart = fitStart
                val taskEnd   = taskStart + durationMs
                // Trim any sleep events that overlap [taskStart, taskEnd].
                scheduled
                    .filter { it.event.category == EventCategory.SLEEP &&
                              it.endMillis > taskStart && it.startMillis < taskEnd }
                    .toList()
                    .forEach { sleepSlot ->
                        scheduled.removeIf { it.event.id == sleepSlot.event.id }
                        if (sleepSlot.startMillis < taskStart &&
                            taskStart - sleepSlot.startMillis >= 30 * 60_000L)
                            scheduled += ScheduledEvent(sleepSlot.event, sleepSlot.startMillis, taskStart)
                        if (sleepSlot.endMillis > taskEnd &&
                            sleepSlot.endMillis - taskEnd >= 30 * 60_000L)
                            scheduled += ScheduledEvent(sleepSlot.event, taskEnd, sleepSlot.endMillis)
                    }
                scheduled += ScheduledEvent(event, taskStart, taskEnd)
                // Carve the placed slot from remaining so regular events don't reuse it.
                val placed = listOf(taskStart to taskEnd)
                val updated = remaining.flatMap { (s, e) -> subtractIntervals(s, e, placed) }
                remaining.clear(); remaining.addAll(updated)
                cursor = taskEnd
            }
        } else {
            afterShiftEligible.forEach { blocked += BlockedEvent(it, "No shift end time") }
        }

        // ── Regular event placement ──────────────────────────────────────────────
        for (event in regularEligible) {
            val durationMs     = event.durationMinutes * 60_000L
            val notDuringShift = event.conditions.any { it is EventCondition.NotDuringShift }
            val beforeShift    = event.conditions.any { it is EventCondition.BeforeShift }
            val tw = event.conditions.filterIsInstance<EventCondition.TimeWindow>().firstOrNull()
            var placed = false

            for (i in remaining.indices) {
                val (blockStart, blockEnd) = remaining[i]

                if (beforeShift && shiftStartMs != null && blockStart >= shiftStartMs) continue

                val fitStart: Long
                val fitEnd: Long
                if (tw != null) {
                    fitStart = maxOf(blockStart, toMs(tw.startHour, tw.startMin))
                    fitEnd   = minOf(blockEnd,   toMs(tw.endHour,   tw.endMin))
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

        // Place DuringShift events inside the shift block
        val shiftRemaining = if (shiftStartMs != null && shiftEndMs != null)
            mutableListOf(shiftStartMs to shiftEndMs) else mutableListOf()

        for (event in duringShiftEligible.sortedByDescending { it.priority }) {
            val durationMs = event.durationMinutes * 60_000L
            val tw = event.conditions.filterIsInstance<EventCondition.TimeWindow>().firstOrNull()
            var placed = false

            for (i in shiftRemaining.indices) {
                val (bStart, bEnd) = shiftRemaining[i]
                val fitStart = if (tw != null) maxOf(bStart, toMs(tw.startHour, tw.startMin)) else bStart
                val fitEnd   = if (tw != null) minOf(bEnd,   toMs(tw.endHour,   tw.endMin))   else bEnd
                if (fitEnd - fitStart < durationMs) continue
                scheduled += ScheduledEvent(event, fitStart, fitStart + durationMs)
                shiftRemaining[i] = (fitStart + durationMs) to bEnd
                placed = true
                break
            }

            if (!placed) blocked += BlockedEvent(event, "No available time in shift")
        }

        // Urgent tasks (priority > SLEEP) that still couldn't be placed may displace sleep.
        // DuringShift urgents are excluded — sleeping during a shift makes no sense.
        val urgentUnplaced = blocked.filter { b ->
            b.event.priority > PlannerPriority.SLEEP &&
            !b.event.conditions.any { it is EventCondition.DuringShift }
        }
        if (urgentUnplaced.isNotEmpty()) {
            blocked.removeAll { b ->
                b.event.priority > PlannerPriority.SLEEP &&
                !b.event.conditions.any { it is EventCondition.DuringShift }
            }
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
                    // Remove the original sleep entry; re-add trimmed remainder if ≥ 30 min.
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

    /** Returns segments of [start, end) with all intervals in [subtract] removed. */
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

    private fun checkDayConditions(event: PlannerEvent, date: LocalDate, isWorkDay: Boolean): String? {
        for (cond in event.conditions) when (cond) {
            is EventCondition.WorkDayOnly -> if (!isWorkDay) return "Work days only"
            is EventCondition.DayOffOnly  -> if (isWorkDay)  return "Days off only"
            is EventCondition.DaysOfWeek  -> if (date.dayOfWeek.value !in cond.days) return "Not scheduled for today"
            is EventCondition.DuringShift,
            is EventCondition.BeforeShift,
            is EventCondition.AfterShift  -> if (!isWorkDay) return "No shift today"
            else -> Unit
        }
        return null
    }
}
