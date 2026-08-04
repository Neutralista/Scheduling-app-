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
        shiftEndMs: Long? = null,
        calendarEventBlocks: Map<Long, Pair<Long, Long>> = emptyMap(),
        reservingBlocks: List<Pair<Long, Long>> = emptyList()
    ): DayPlan = planForDate(LocalDate.now(), isWorkDay, shiftStartMs, shiftEndMs, calendarEventBlocks, reservingBlocks)

    fun planForDate(
        date: LocalDate,
        isWorkDay: Boolean = false,
        shiftStartMs: Long? = null,
        shiftEndMs: Long? = null,
        calendarEventBlocks: Map<Long, Pair<Long, Long>> = emptyMap(),
        reservingBlocks: List<Pair<Long, Long>> = emptyList()
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
        val dayEndMs   = dayStartMs + 24 * 3600_000L

        val blocked        = mutableListOf<BlockedEvent>()
        val scheduled      = mutableListOf<ScheduledEvent>()
        val allSchedulable = mutableListOf<PlannerEvent>()

        // ── Fixed events + day-condition filtering ────────────────────────────
        for (event in _events) {
            if (event.fixedStartMillis != null && event.fixedEndMillis != null) {
                val start = maxOf(event.fixedStartMillis, dayStartMs)
                val end   = minOf(event.fixedEndMillis,   dayEndMs)
                if (end > start) scheduled += ScheduledEvent(event, start, end)
                continue
            }
            val reason = checkDayConditions(event, date, isWorkDay, shiftStartMs, shiftEndMs)
            if (reason != null) { blocked += BlockedEvent(event, reason); continue }
            allSchedulable += event
        }

        // ── Free blocks start at wake time (end of sleep), not midnight ───────
        val cycleStartMs = scheduled
            .filter { it.event.category == EventCategory.SLEEP && it.endMillis > dayStartMs }
            .maxOfOrNull { it.endMillis } ?: dayStartMs

        // BUFFER events are visual-only and do not block scheduling.
        // reservingBlocks carries calendar events the user marked as "reserves time".
        val fixedIntervals = scheduled
            .filter { it.event.category != EventCategory.BUFFER }
            .map { it.startMillis to it.endMillis } + reservingBlocks
        val remaining = subtractIntervals(cycleStartMs, dayEndMs, fixedIntervals).toMutableList()

        // ── Build dependency graph ────────────────────────────────────────────
        //
        // Edge A → B means "A must be scheduled before B".
        // Sources:
        //   BeforeTask[B] on A  → A→B  (A precedes B in time)
        //   AfterTask[A]  on B  → A→B  (B follows A in time)
        //   SameDayAs[X]  on E  → X→E  (X must be resolved before eligibility of E is known)
        //   NotSameDayAs[X] on E→ X→E
        //
        // implicitAfter[id] answers: "which already-placed events constrain id's start time
        // from below?" — populated from BeforeTask so the referenced event inherits a lower
        // bound automatically, without needing its own AfterTask condition.

        val allIds = allSchedulable.map { it.id }.toSet()
        val predecessors  = allSchedulable.associate { it.id to mutableSetOf<String>() }.toMutableMap()
        val implicitAfter = allSchedulable.associate { it.id to mutableSetOf<String>() }.toMutableMap()

        for (event in allSchedulable) {
            for (cond in event.conditions) when (cond) {
                is EventCondition.BeforeTask -> cond.taskIds.filter { it in allIds }.forEach { targetId ->
                    predecessors.getOrPut(targetId) { mutableSetOf() }  += event.id
                    implicitAfter.getOrPut(targetId) { mutableSetOf() } += event.id
                }
                is EventCondition.AfterTask -> cond.taskIds
                    .filter { it in allIds && it != TASK_REF_SLEEP }
                    .forEach { depId -> predecessors.getOrPut(event.id) { mutableSetOf() } += depId }
                is EventCondition.SameDayAs -> cond.taskIds.filter { it in allIds }
                    .forEach { depId -> predecessors.getOrPut(event.id) { mutableSetOf() } += depId }
                is EventCondition.NotSameDayAs -> cond.taskIds.filter { it in allIds }
                    .forEach { depId -> predecessors.getOrPut(event.id) { mutableSetOf() } += depId }
                else -> Unit
            }
        }

        // ── Kahn's topological sort (priority-ordered within the same depth) ──
        val inDegree = predecessors.mapValues { it.value.size }.toMutableMap()
        val ready = allSchedulable
            .filter { (inDegree[it.id] ?: 0) == 0 }
            .sortedByDescending { it.priority }
            .toMutableList()
        val order = mutableListOf<PlannerEvent>()

        while (ready.isNotEmpty()) {
            val event = ready.removeFirst()
            order += event
            for (other in allSchedulable) {
                if (event.id !in (predecessors[other.id] ?: emptySet())) continue
                val deg = (inDegree[other.id] ?: 0) - 1
                inDegree[other.id] = deg
                if (deg == 0) {
                    val idx = ready.indexOfFirst { it.priority < other.priority }
                    if (idx < 0) ready += other else ready.add(idx, other)
                }
            }
        }

        // Cyclic dependencies — block the participants
        (allSchedulable.toSet() - order.toSet()).forEach {
            blocked += BlockedEvent(it, "Cyclic dependency")
        }

        // ── Single scheduling pass in dependency order ────────────────────────
        for (event in order) {
            val durationMs  = event.durationMinutes * 60_000L
            val tw          = event.conditions.filterIsInstance<EventCondition.TimeWindow>().firstOrNull()
            val beforeShift = event.conditions.any { it is EventCondition.BeforeShift }
            val afterShift  = event.conditions.any { it is EventCondition.AfterShift }
            val duringShift = event.conditions.any { it is EventCondition.DuringShift }

            // SameDayAs / NotSameDayAs eligibility (checked against already-placed set)
            val scheduledIds = scheduled.map { it.event.id }.toSet()
            val sameDayAs    = event.conditions.filterIsInstance<EventCondition.SameDayAs>().firstOrNull()
            val notSameDayAs = event.conditions.filterIsInstance<EventCondition.NotSameDayAs>().firstOrNull()
            if (sameDayAs    != null && !sameDayAs.taskIds.all   { it in scheduledIds }) {
                blocked += BlockedEvent(event, "Required tasks not scheduled today"); continue
            }
            if (notSameDayAs != null &&  notSameDayAs.taskIds.any { it in scheduledIds }) {
                blocked += BlockedEvent(event, "Excluded tasks are scheduled today"); continue
            }

            // Sleep sentinel bounds
            val sleepStartBound = scheduled.filter { it.event.category == EventCategory.SLEEP }.minOfOrNull { it.startMillis }
            val sleepEndBound   = scheduled.filter { it.event.category == EventCategory.SLEEP }.maxOfOrNull { it.endMillis }

            // Explicit time bounds from BeforeTask / AfterTask conditions
            val beforeTask = event.conditions.filterIsInstance<EventCondition.BeforeTask>().firstOrNull()
            val afterTask  = event.conditions.filterIsInstance<EventCondition.AfterTask>().firstOrNull()
            val mustEndBefore = beforeTask?.taskIds?.mapNotNull { id ->
                if (id == TASK_REF_SLEEP) sleepStartBound
                else scheduled.find { it.event.id == id }?.startMillis
            }?.minOrNull()
            val explicitMustStartAfter = afterTask?.taskIds?.mapNotNull { id ->
                if (id == TASK_REF_SLEEP) sleepEndBound
                else scheduled.find { it.event.id == id }?.endMillis
            }?.maxOrNull()

            // Implicit lower bound: events that declared BeforeTask[this] are now placed;
            // this event must start after the latest of their end times.
            val implicitMustStartAfter = implicitAfter[event.id]
                ?.mapNotNull { predId -> scheduled.find { it.event.id == predId }?.endMillis }
                ?.maxOrNull()

            val mustStartAfter = listOfNotNull(explicitMustStartAfter, implicitMustStartAfter).maxOrNull()

            // Merge BeforeCalEvent / AfterCalEvent bounds with task-based bounds
            val calMustEndBefore  = event.conditions.filterIsInstance<EventCondition.BeforeCalEvent>()
                .mapNotNull { calendarEventBlocks[it.eventId]?.first }.minOrNull()
            val calMustStartAfter = event.conditions.filterIsInstance<EventCondition.AfterCalEvent>()
                .mapNotNull { calendarEventBlocks[it.eventId]?.second }.maxOrNull()
            val effectiveMustEndBefore  = listOfNotNull(mustEndBefore,  calMustEndBefore).minOrNull()
            val effectiveMustStartAfter = listOfNotNull(mustStartAfter, calMustStartAfter).maxOrNull()

            var placed = false

            // DuringShift: constrained to the shift window only
            if (duringShift && shiftStartMs != null && shiftEndMs != null) {
                val fitStart = if (tw != null) maxOf(shiftStartMs, toMs(tw.startHour, tw.startMin)) else shiftStartMs
                val fitEnd   = if (tw != null) minOf(shiftEndMs,   toMs(tw.endHour,   tw.endMin))   else shiftEndMs
                if (fitEnd - fitStart >= durationMs) {
                    scheduled += ScheduledEvent(event, fitStart, fitStart + durationMs)
                    placed = true
                }
                if (!placed) blocked += BlockedEvent(event, "No available time in shift")
                continue
            }

            // DuringCalEvent: constrained to a specific calendar event's reserved slot
            val duringCalEvent = event.conditions.filterIsInstance<EventCondition.DuringCalEvent>().firstOrNull()
            if (duringCalEvent != null) {
                val slot = calendarEventBlocks[duringCalEvent.eventId]
                if (slot == null) {
                    blocked += BlockedEvent(event, "Calendar event not found today")
                } else {
                    val fitStart = if (tw != null) maxOf(slot.first, toMs(tw.startHour, tw.startMin)) else slot.first
                    val fitEnd   = if (tw != null) minOf(slot.second, toMs(tw.endHour,   tw.endMin))  else slot.second
                    if (fitEnd - fitStart >= durationMs) {
                        scheduled += ScheduledEvent(event, fitStart, fitStart + durationMs)
                        placed = true
                    }
                    if (!placed) blocked += BlockedEvent(event, "No available time in calendar event slot")
                }
                continue
            }

            for (i in remaining.indices) {
                val (blockStart, blockEnd) = remaining[i]

                if (beforeShift && shiftStartMs != null && blockStart >= shiftStartMs) continue
                if (afterShift  && shiftEndMs   != null && blockEnd   <= shiftEndMs)   continue

                val fitStart = maxOf(
                    blockStart,
                    effectiveMustStartAfter ?: blockStart,
                    tw?.let { toMs(it.startHour, it.startMin) } ?: blockStart,
                    if (afterShift && shiftEndMs != null) shiftEndMs else blockStart
                )
                val fitEnd = minOf(
                    blockEnd,
                    effectiveMustEndBefore ?: blockEnd,
                    tw?.let { toMs(it.endHour, it.endMin) } ?: blockEnd,
                    if (beforeShift && shiftStartMs != null) shiftStartMs else blockEnd
                )
                val deadline = event.conditions.filterIsInstance<EventCondition.Deadline>().firstOrNull()
                if (deadline != null && fitStart + durationMs > deadline.byMillis) continue
                if (fitEnd - fitStart < durationMs) continue

                scheduled += ScheduledEvent(event, fitStart, fitStart + durationMs)
                remaining[i] = (fitStart + durationMs) to blockEnd
                placed = true
                break
            }

            if (!placed) blocked += BlockedEvent(event, "No available time slot")
        }

        // ── Urgent tasks above SLEEP priority may displace sleep windows ───────
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
