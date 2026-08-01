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

        val (duringShiftEligible, regularEligible) = eligible.partition { e ->
            e.conditions.any { it is EventCondition.DuringShift }
        }

        val remaining = freeBlocks.map { it.startMillis to it.endMillis }.toMutableList()

        for (event in regularEligible) {
            val durationMs     = event.durationMinutes * 60_000L
            val notDuringShift = event.conditions.any { it is EventCondition.NotDuringShift }
            val beforeShift    = event.conditions.any { it is EventCondition.BeforeShift }
            val afterShift     = event.conditions.any { it is EventCondition.AfterShift }
            val tw = event.conditions.filterIsInstance<EventCondition.TimeWindow>().firstOrNull()
            var placed = false

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
