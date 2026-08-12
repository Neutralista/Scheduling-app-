package com.waypoint.app.planner

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Calendar
import kotlin.math.roundToInt

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
        reservingBlocks: List<Pair<Long, Long>> = emptyList(),
        namedBlockInstances: List<NamedBlockInstance> = emptyList(),
        floatingBlocks: List<NamedBlockInstance> = emptyList()
    ): DayPlan = planForDate(
        LocalDate.now(), isWorkDay, shiftStartMs, shiftEndMs, calendarEventBlocks, reservingBlocks,
        nowMs = System.currentTimeMillis(),
        namedBlockInstances = namedBlockInstances,
        floatingBlocks = floatingBlocks
    )

    fun planForDate(
        date: LocalDate,
        isWorkDay: Boolean = false,
        shiftStartMs: Long? = null,
        shiftEndMs: Long? = null,
        calendarEventBlocks: Map<Long, Pair<Long, Long>> = emptyMap(),
        reservingBlocks: List<Pair<Long, Long>> = emptyList(),
        nowMs: Long? = null,
        namedBlockInstances: List<NamedBlockInstance> = emptyList(),
        floatingBlocks: List<NamedBlockInstance> = emptyList()
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
        // Only consider sleep events that have already ended (past sleep gives today's wake time).
        // Exclude future sleep events — they anchor the END of today's cycle, not the start.
        val cycleStartMs = scheduled
            .filter { it.event.category == EventCategory.SLEEP
                   && it.endMillis > dayStartMs
                   && (nowMs == null || it.endMillis <= nowMs) }
            .maxOfOrNull { it.endMillis } ?: dayStartMs

        // Tonight's sleep event — the first sleep window that begins after this morning's wake.
        // bed time is treated as a soft preference: tasks can push it later, shortening sleep
        // down to a 4-hour minimum.  Alarms and the sleep registry are not touched.
        val tonightSleepEvent = _events
            .filter { it.category == EventCategory.SLEEP
                    && it.fixedStartMillis != null
                    && it.fixedStartMillis!! > cycleStartMs }
            .minByOrNull { it.fixedStartMillis!! }
        val preferredBedMs = tonightSleepEvent?.fixedStartMillis
        val sleepWakeMs    = tonightSleepEvent?.fixedEndMillis

        // Extend the schedulable window to wake time so tasks can run into the sleep block.
        // After the main pass the sleep block is slid forward to match the latest task end.
        val freeBlockEnd = sleepWakeMs ?: dayEndMs

        // When nowMs is provided (today's live view), past time is not schedulable.
        // Tasks that would have started before now are placed starting from now instead,
        // so the plan always reflects what can still realistically happen. The priority-
        // ordered greedy pass then ensures the most important tasks claim the shrinking
        // window first — least-priority tasks fall off the end naturally.
        val planStartMs = if (nowMs != null) maxOf(cycleStartMs, nowMs) else cycleStartMs

        // BUFFER events are visual-only and do not block scheduling.
        // reservingBlocks carries calendar events the user marked as "reserves time".
        // Tonight's sleep is excluded so tasks can freely schedule into that window;
        // it is re-inserted at its final (slid) position after the scheduling pass.
        val fixedIntervals = scheduled
            .filter { it.event.category != EventCategory.BUFFER
                   && it.event.id != tonightSleepEvent?.id }
            .map { it.startMillis to it.endMillis } + reservingBlocks
        val remaining = subtractIntervals(planStartMs, freeBlockEnd, fixedIntervals).toMutableList()

        // ── Floating named blocks ──────────────────────────────────────────────
        // Blocks with isFloating=true have no fixed schedule; place them in available
        // time using first-fit before tasks run, ordered by block priority descending.
        val floatingResolved = mutableListOf<NamedBlockInstance>()
        for (inst in floatingBlocks.sortedByDescending { it.block.priority }) {
            val eligible = inst.block.floatingConditions.none { spec ->
                when (spec.type) {
                    "workDayOnly" -> !isWorkDay
                    "dayOffOnly"  -> isWorkDay
                    "daysOfWeek"  -> spec.days?.let { date.dayOfWeek.value !in it } ?: false
                    else -> false
                }
            }
            if (!eligible) continue
            val effectiveDurMins = if (inst.block.useTotalTaskDuration && inst.activeTasks.any { it.placement == BlockTaskPlacement.DURING })
                inst.activeTasks.filter { it.placement == BlockTaskPlacement.DURING }.sumOf { it.durationMinutes }
                else inst.block.estimatedMinutes
            val durationMs = effectiveDurMins * 60_000L
            val tw = inst.block.floatingConditions.firstOrNull { it.type == "timeWindow" }
            val beforeBlockCond = inst.block.floatingConditions.firstOrNull { it.type == "beforeBlock" }
            val afterBlockCond  = inst.block.floatingConditions.firstOrNull { it.type == "afterBlock" }
            val floatUpperBound = beforeBlockCond?.blockId?.let { refId ->
                (namedBlockInstances + floatingResolved).find { it.block.id == refId }?.scheduledStartMs
            }
            val floatLowerBound = afterBlockCond?.blockId?.let { refId ->
                (namedBlockInstances + floatingResolved).find { it.block.id == refId }?.estimatedEndMs
            }
            var placed = false
            for (i in remaining.indices) {
                val (bStart, bEnd) = remaining[i]
                val fitStart = maxOf(
                    if (tw?.start != null) {
                        val p = tw.start!!.split(":"); val h = p[0].toIntOrNull() ?: 0; val m = p.getOrNull(1)?.toIntOrNull() ?: 0
                        maxOf(bStart, toMs(h, m))
                    } else bStart,
                    floatLowerBound ?: bStart
                )
                val fitEnd = minOf(
                    if (tw?.end != null) {
                        val p = tw.end!!.split(":"); val h = p[0].toIntOrNull() ?: 23; val m = p.getOrNull(1)?.toIntOrNull() ?: 59
                        minOf(bEnd, toMs(h, m))
                    } else bEnd,
                    floatUpperBound ?: bEnd
                )
                if (fitEnd - fitStart < durationMs) continue
                floatingResolved += NamedBlockInstance(
                    block = inst.block,
                    scheduledStartMs = fitStart,
                    estimatedEndMs = fitStart + durationMs,
                    activeTasks = inst.activeTasks
                )
                remaining[i] = (fitStart + durationMs) to bEnd
                placed = true
                break
            }
            if (!placed) {
                blocked += BlockedEvent(
                    PlannerEvent(
                        id = "__block__${inst.block.id}",
                        title = inst.block.name,
                        durationMinutes = effectiveDurMins,
                        priority = inst.block.priority,
                        category = EventCategory.BLOCK
                    ),
                    "No available time slot for floating block"
                )
            }
        }

        // ── Named block instances ─────────────────────────────────────────────
        // Each block is placed as a placeholder scheduled event at its scheduled start.
        // Its tasks are injected into allSchedulable with synthesised conditions so the
        // main greedy pass positions them relative to the block boundaries.
        // After the pass we expand/contract each block's displayed extent.
        val allBlockInstances = namedBlockInstances + floatingResolved
        val blockEventPrefix = "__block__"
        for (inst in allBlockInstances) {
            val blockEventId = "$blockEventPrefix${inst.block.id}"
            val blockPlannerEvent = PlannerEvent(
                id = blockEventId,
                title = inst.block.name,
                durationMinutes = ((inst.estimatedEndMs - inst.scheduledStartMs) / 60_000L).toInt(),
                priority = 8,
                category = EventCategory.BLOCK,
                fixedStartMillis = inst.scheduledStartMs,
                fixedEndMillis = inst.estimatedEndMs
            )
            scheduled += ScheduledEvent(blockPlannerEvent, inst.scheduledStartMs, inst.estimatedEndMs)
            // Block itself occupies time — add to fixedIntervals so regular tasks don't overlap
            // (AfterBlock tasks will be scheduled after estimatedEndMs via condition)

            for (task in inst.activeTasks) {
                val placementCond: EventCondition = when (task.placement) {
                    BlockTaskPlacement.BEFORE -> EventCondition.BeforeBlock(inst.block.id)
                    BlockTaskPlacement.DURING -> EventCondition.DuringBlock(inst.block.id)
                    BlockTaskPlacement.AFTER  -> EventCondition.AfterBlock(inst.block.id)
                }
                val extraConds = task.conditions.mapNotNull { it.toEventCondition() }
                val blockZone = if (task.placement == BlockTaskPlacement.DURING) {
                    when (task.subPlacement) {
                        BlockSubPlacement.MID -> PlannerZone.AFTERNOON
                        BlockSubPlacement.END -> PlannerZone.EVENING
                        else                  -> null
                    }
                } else null
                val syntheticEvent = PlannerEvent(
                    id = task.id,
                    title = task.title,
                    durationMinutes = task.durationMinutes,
                    priority = task.priority,
                    bufferMinutes = task.bufferMinutes,
                    conditions = listOf(placementCond) + extraConds,
                    sourceWidgetId = blockEventId,
                    zone = blockZone,
                    colorArgb = task.colorArgb
                )
                val dayReason = checkDayConditions(syntheticEvent, date, isWorkDay, shiftStartMs, shiftEndMs)
                if (dayReason != null) blocked += BlockedEvent(syntheticEvent, dayReason)
                else allSchedulable += syntheticEvent
            }
        }
        // Re-build fixedIntervals to include block bodies (DuringBlock tasks will carve into them)
        val blockFixedIntervals = allBlockInstances.map { it.scheduledStartMs to it.estimatedEndMs }
        val fixedIntervalsWithBlocks = fixedIntervals + blockFixedIntervals
        val remainingWithBlocks = subtractIntervals(planStartMs, freeBlockEnd, fixedIntervalsWithBlocks).toMutableList()
        // Use remainingWithBlocks as the working set from here on
        remaining.clear(); remaining.addAll(remainingWithBlocks)

        // Historical slots: free time between cycleStartMs and planStartMs that has already
        // passed relative to nowMs. Regular tasks cannot be scheduled here, but BEFORE block
        // tasks need this window — their deadline is the block start, which may be in the past
        // when planStartMs > blockStartMs (i.e. the session is already active).
        val historicalSlots: MutableList<Pair<Long, Long>> = if (planStartMs > cycleStartMs)
            subtractIntervals(cycleStartMs, planStartMs, fixedIntervalsWithBlocks).toMutableList()
            else mutableListOf()

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

        // Per-block free slot lists for DuringBlock task placement (prevents overlap)
        val blockFreeSlots: MutableMap<String, MutableList<Pair<Long, Long>>> = mutableMapOf()
        for (inst in allBlockInstances) {
            blockFreeSlots[inst.block.id] = mutableListOf(inst.scheduledStartMs to inst.estimatedEndMs)
        }
        // Free slot list for DuringShift task placement (prevents overlap)
        val shiftFreeSlots: MutableList<Pair<Long, Long>> = if (shiftStartMs != null && shiftEndMs != null)
            mutableListOf(shiftStartMs to shiftEndMs) else mutableListOf()

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

            // Sleep sentinel bounds for BeforeTask[SLEEP] / AfterTask[SLEEP].
            // sleepStartBound = preferred bed time (the soft target for "before sleep" tasks).
            // sleepEndBound   = this morning's wake time (after sleep → after today's wake).
            val sleepStartBound = preferredBedMs
                ?: scheduled.filter { it.event.category == EventCategory.SLEEP
                                   && it.event.id != tonightSleepEvent?.id }
                    .minOfOrNull { it.startMillis }
            val sleepEndBound = cycleStartMs

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

            // Merge BeforeCalEvent / AfterCalEvent / BeforeBlock / AfterBlock bounds
            val calMustEndBefore  = event.conditions.filterIsInstance<EventCondition.BeforeCalEvent>()
                .mapNotNull { calendarEventBlocks[it.eventId]?.first }.minOrNull()
            val calMustStartAfter = event.conditions.filterIsInstance<EventCondition.AfterCalEvent>()
                .mapNotNull { calendarEventBlocks[it.eventId]?.second }.maxOrNull()
            val beforeBlock = event.conditions.filterIsInstance<EventCondition.BeforeBlock>().firstOrNull()
            val afterBlock  = event.conditions.filterIsInstance<EventCondition.AfterBlock>().firstOrNull()
            val blockMustEndBefore = beforeBlock?.let { cond ->
                allBlockInstances.find { it.block.id == cond.blockId }?.scheduledStartMs
            }
            val blockMustStartAfter = afterBlock?.let { cond ->
                allBlockInstances.find { it.block.id == cond.blockId }?.estimatedEndMs
            }
            if (beforeBlock != null && blockMustEndBefore == null) {
                blocked += BlockedEvent(event, "Named block not scheduled today"); continue
            }
            if (afterBlock != null && blockMustStartAfter == null) {
                blocked += BlockedEvent(event, "Named block not scheduled today"); continue
            }
            val effectiveMustEndBefore = listOfNotNull(
                mustEndBefore, calMustEndBefore, blockMustEndBefore
            ).minOrNull()
            val effectiveMustStartAfterFromBlocks = listOfNotNull(calMustStartAfter, blockMustStartAfter).maxOrNull()

            // Zone: soft time-of-day anchor.
            // MORNING  → no extra lower bound (first-fit from wake, same as default)
            // AFTERNOON → lower bound at one-third of the free window, but only when no
            //             explicit TimeWindow is set — an explicit window already constrains
            //             placement, and effectiveMustEndBefore triggers last-fit anyway,
            //             so the zone lower bound would only create impossible conflicts.
            // EVENING   → last-fit (mirrors scheduleLate; no additional lower bound needed)
            // Base zone span on preferred bed time, not the extended wake-to-wake window,
            // so AFTERNOON places tasks in the real active part of the day.
            val freeSpan = (preferredBedMs ?: freeBlockEnd) - cycleStartMs
            val hasExplicitTimeWindow = event.conditions.any { it is EventCondition.TimeWindow }
            val zoneLowerBound: Long? = when {
                event.zone == PlannerZone.AFTERNOON && !hasExplicitTimeWindow ->
                    cycleStartMs + freeSpan / 3
                else -> null
            }

            val effectiveMustStartAfter = listOfNotNull(
                mustStartAfter, effectiveMustStartAfterFromBlocks, zoneLowerBound
            ).maxOrNull()

            // Last-fit when an upper-bound constraint exists, the event prefers to land
            // late (scheduleLate), or the zone is EVENING.
            val useLast = effectiveMustEndBefore != null
                    || event.scheduleLate
                    || event.zone == PlannerZone.EVENING

            var placed = false

            // DuringShift: greedy first-fit within shift free slots to prevent overlap
            if (duringShift && shiftStartMs != null && shiftEndMs != null) {
                for (i in shiftFreeSlots.indices) {
                    val (slotStart, slotEnd) = shiftFreeSlots[i]
                    val fitStart = if (tw != null) maxOf(slotStart, toMs(tw.startHour, tw.startMin)) else slotStart
                    val fitEnd   = if (tw != null) minOf(slotEnd,   toMs(tw.endHour,   tw.endMin))   else slotEnd
                    if (fitEnd - fitStart < durationMs) continue
                    scheduled += ScheduledEvent(event, fitStart, fitStart + durationMs)
                    shiftFreeSlots[i] = (fitStart + durationMs) to slotEnd
                    placed = true
                    break
                }
                if (!placed) blocked += BlockedEvent(event, "No available time in shift")
                continue
            }

            // DuringBlock: greedy first-fit (or last-fit for END zone) within per-block free slots
            val duringBlock = event.conditions.filterIsInstance<EventCondition.DuringBlock>().firstOrNull()
            if (duringBlock != null) {
                val inst = allBlockInstances.find { it.block.id == duringBlock.blockId }
                if (inst == null) {
                    blocked += BlockedEvent(event, "Named block not scheduled today"); continue
                }
                val slots = blockFreeSlots.getOrPut(inst.block.id) {
                    mutableListOf(inst.scheduledStartMs to inst.estimatedEndMs)
                }
                val blockDuration = inst.estimatedEndMs - inst.scheduledStartMs
                // MID: lower bound at 1/3 of block window; END: last-fit
                val blockZoneLower: Long? = if (event.zone == PlannerZone.AFTERNOON)
                    inst.scheduledStartMs + blockDuration / 3 else null
                val useBlockLast = event.zone == PlannerZone.EVENING
                if (!useBlockLast) {
                    for (i in slots.indices) {
                        val (slotStart, slotEnd) = slots[i]
                        val fitStart = maxOf(
                            if (tw != null) maxOf(slotStart, toMs(tw.startHour, tw.startMin)) else slotStart,
                            blockZoneLower ?: slotStart
                        )
                        val fitEnd = if (tw != null) minOf(slotEnd, toMs(tw.endHour, tw.endMin)) else slotEnd
                        if (fitEnd - fitStart < durationMs) continue
                        scheduled += ScheduledEvent(event, fitStart, fitStart + durationMs)
                        slots[i] = (fitStart + durationMs) to slotEnd
                        placed = true
                        break
                    }
                } else {
                    for (i in slots.indices.reversed()) {
                        val (slotStart, slotEnd) = slots[i]
                        val lo = if (tw != null) maxOf(slotStart, toMs(tw.startHour, tw.startMin)) else slotStart
                        val hi = if (tw != null) minOf(slotEnd, toMs(tw.endHour, tw.endMin)) else slotEnd
                        val lateStart = hi - durationMs
                        if (lateStart < lo) continue
                        scheduled += ScheduledEvent(event, lateStart, lateStart + durationMs)
                        slots.removeAt(i)
                        val segs = buildList {
                            if (lateStart > slotStart) add(slotStart to lateStart)
                            if (lateStart + durationMs < slotEnd) add((lateStart + durationMs) to slotEnd)
                        }
                        slots.addAll(i, segs)
                        placed = true
                        break
                    }
                }
                if (!placed) blocked += BlockedEvent(event, "No available time in block window")
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

            val deadline = event.conditions.filterIsInstance<EventCondition.Deadline>().firstOrNull()

            if (!useLast) {
                // Forward first-fit: take the earliest block where the task fits.
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
                    if (deadline != null && fitStart + durationMs > deadline.byMillis) continue
                    if (fitEnd - fitStart < durationMs) continue
                    scheduled += ScheduledEvent(event, fitStart, fitStart + durationMs)
                    remaining[i] = (fitStart + durationMs) to blockEnd
                    placed = true
                    break
                }
            } else {
                // Reverse last-fit: place as late as possible before any upper bound.
                // Two-phase: phase 1 tries to stay before preferredBedMs (pre-sleep); phase 2
                // allows overflow into the sleep window only when there is genuinely no room.
                val bedCaps = if (preferredBedMs != null && effectiveMustEndBefore == null)
                    listOf(preferredBedMs, null) else listOf(null)
                outer@ for (bedCap in bedCaps) {
                    for (i in remaining.indices.reversed()) {
                        val (blockStart, blockEnd) = remaining[i]
                        if (beforeShift && shiftStartMs != null && blockStart >= shiftStartMs) continue
                        if (afterShift  && shiftEndMs   != null && blockEnd   <= shiftEndMs)   continue
                        val upperBound = minOf(
                            blockEnd,
                            effectiveMustEndBefore ?: (bedCap ?: blockEnd),
                            tw?.let { toMs(it.endHour, it.endMin) } ?: blockEnd,
                            if (beforeShift && shiftStartMs != null) shiftStartMs else blockEnd
                        )
                        val lowerBound = maxOf(
                            blockStart,
                            effectiveMustStartAfter ?: blockStart,
                            tw?.let { toMs(it.startHour, it.startMin) } ?: blockStart,
                            if (afterShift && shiftEndMs != null) shiftEndMs else blockStart
                        )
                        val fitStart = upperBound - durationMs
                        if (deadline != null && fitStart + durationMs > deadline.byMillis) continue
                        if (fitStart < lowerBound) continue
                        scheduled += ScheduledEvent(event, fitStart, fitStart + durationMs)
                        // Preserve free time before and after the placed slot.
                        remaining.removeAt(i)
                        val newSegs = buildList {
                            if (fitStart > blockStart) add(blockStart to fitStart)
                            if (fitStart + durationMs < blockEnd) add((fitStart + durationMs) to blockEnd)
                        }
                        remaining.addAll(i, newSegs)
                        placed = true
                        break@outer
                    }
                }
            }

            // BEFORE block tasks: if the main pass failed because planStartMs > blockStartMs
            // (i.e. the block has already started), fall back to historical slots.
            if (!placed && beforeBlock != null && historicalSlots.isNotEmpty()) {
                for (i in historicalSlots.indices.reversed()) {
                    val (slotStart, slotEnd) = historicalSlots[i]
                    val lo = maxOf(
                        slotStart,
                        effectiveMustStartAfter ?: slotStart,
                        tw?.let { toMs(it.startHour, it.startMin) } ?: slotStart
                    )
                    val hi = minOf(
                        slotEnd,
                        effectiveMustEndBefore ?: slotEnd,
                        tw?.let { toMs(it.endHour, it.endMin) } ?: slotEnd
                    )
                    val fitStart = hi - durationMs
                    if (fitStart < lo) continue
                    scheduled += ScheduledEvent(event, fitStart, fitStart + durationMs)
                    historicalSlots.removeAt(i)
                    val segs = buildList {
                        if (fitStart > slotStart) add(slotStart to fitStart)
                        if (fitStart + durationMs < slotEnd) add((fitStart + durationMs) to slotEnd)
                    }
                    historicalSlots.addAll(i, segs)
                    placed = true
                    break
                }
            }

            if (!placed) blocked += BlockedEvent(event, "No available time slot")
        }

        // ── Post-pass: expand named block bounds to wrap BEFORE/AFTER tasks ────
        // BEFORE tasks that were placed before the block's scheduled start pull the
        // displayed block start earlier (canStartEarly); AFTER tasks that overran push
        // the displayed block end later (canRunLate).
        for (inst in allBlockInstances) {
            val blockEventId = "$blockEventPrefix${inst.block.id}"
            val beforeTaskIds = inst.activeTasks
                .filter { it.placement == BlockTaskPlacement.BEFORE }.map { it.id }.toSet()
            val afterTaskIds  = inst.activeTasks
                .filter { it.placement == BlockTaskPlacement.AFTER  }.map { it.id }.toSet()

            val earliestBeforeStart = if (inst.block.canStartEarly && beforeTaskIds.isNotEmpty())
                scheduled.filter { it.event.id in beforeTaskIds }.minOfOrNull { it.startMillis }
            else null
            val latestAfterEnd = if (inst.block.canRunLate && afterTaskIds.isNotEmpty())
                scheduled.filter { it.event.id in afterTaskIds }.maxOfOrNull { it.endMillis }
            else null

            if (earliestBeforeStart != null || latestAfterEnd != null) {
                val newStart = earliestBeforeStart ?: inst.scheduledStartMs
                val newEnd   = latestAfterEnd ?: inst.estimatedEndMs
                scheduled.removeIf { it.event.id == blockEventId }
                scheduled += ScheduledEvent(
                    PlannerEvent(
                        id = blockEventId, title = inst.block.name,
                        durationMinutes = ((newEnd - newStart) / 60_000L).toInt(),
                        priority = 8, category = EventCategory.BLOCK,
                        fixedStartMillis = newStart, fixedEndMillis = newEnd
                    ),
                    newStart, newEnd
                )
            }
        }

        // Slide tonight's sleep block forward if tasks ran past preferred bed time.
        // The 4-hour minimum-sleep guard keeps the block from compressing below a viable rest.
        if (tonightSleepEvent != null && preferredBedMs != null && sleepWakeMs != null) {
            val minSleepMs = 4 * 3600_000L
            val latestAllowedBedMs = sleepWakeMs - minSleepMs
            if (latestAllowedBedMs >= preferredBedMs) {
                val latestTaskEnd = scheduled
                    .filter { it.event.id != tonightSleepEvent.id && it.endMillis <= sleepWakeMs }
                    .maxOfOrNull { it.endMillis } ?: preferredBedMs
                val newBedMs = maxOf(preferredBedMs, minOf(latestTaskEnd, latestAllowedBedMs))
                if (newBedMs != preferredBedMs) {
                    scheduled.removeIf { it.event.id == tonightSleepEvent.id }
                    scheduled += ScheduledEvent(tonightSleepEvent, newBedMs, sleepWakeMs)
                }
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
            is EventCondition.OneOff -> if (date.toString() != cond.date) return "Not scheduled for today"
            is EventCondition.EveryNDays -> {
                val anchor = runCatching { LocalDate.parse(cond.anchorDate) }.getOrNull()
                    ?: return "Invalid anchor date"
                val diff = ChronoUnit.DAYS.between(anchor, date)
                if (diff < 0 || diff % cond.n != 0L) return "Not scheduled for today"
            }
            is EventCondition.EveryNWeeks -> {
                val anchor = runCatching { LocalDate.parse(cond.anchorDate) }.getOrNull()
                    ?: return "Invalid anchor date"
                val diff = ChronoUnit.DAYS.between(anchor, date)
                if (diff < 0 || diff % (cond.n * 7L) != 0L) return "Not scheduled for today"
            }
            is EventCondition.EveryNMonths -> {
                val anchor = runCatching { LocalDate.parse(cond.anchorDate) }.getOrNull()
                    ?: return "Invalid anchor date"
                if (date.dayOfMonth != anchor.dayOfMonth) return "Not scheduled for today"
                val months = ChronoUnit.MONTHS.between(anchor, date)
                if (months < 0 || months % cond.n != 0L) return "Not scheduled for today"
            }
            is EventCondition.NTimesPerPeriod -> {
                val anchor = runCatching { LocalDate.parse(cond.anchorDate) }.getOrNull()
                    ?: return "Invalid anchor date"
                val daysSince = ChronoUnit.DAYS.between(anchor, date)
                if (daysSince < 0) return "Not scheduled for today"
                val dayInPeriod = (daysSince % cond.periodDays).toInt()
                val spacing = cond.periodDays.toDouble() / cond.count
                val matches = (0 until cond.count).any { k -> dayInPeriod == (k * spacing).roundToInt() }
                if (!matches) return "Not scheduled for today"
            }
            else -> Unit
        }
        return null
    }
}
