package com.waypoint.app.planner

import com.waypoint.app.signal.CalendarSignals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * The day's plan exactly as the Plan tab's timeline works it out, for everything else that shows
 * today (the Tasks tab, the header's count) so they agree with it. Each used to plan on its own
 * without calendar time, measured task lengths or today's sessions, and landed things hours
 * away from where the timeline had them.
 */

/** Tasks with "use measured duration" take their average measured length. */
fun List<BlockTask>.withMeasuredLengths(logStore: BlockSessionLogStore?): List<BlockTask> {
    if (logStore == null) return this
    return map { task ->
        if (task.useMeasuredDuration) {
            logStore.averageMeasuredMinutes(task.id)?.let { task.copy(durationMinutes = it) } ?: task
        } else task
    }
}

/**
 * [date]'s blocks as the timeline builds them: (fixed, floating). Fixed ones sit at their
 * scheduled times, or where a session actually ran them today; floating ones a session has
 * already placed (running, or done today) count as fixed; the rest are left to the planner.
 */
fun NamedBlockStore.liveBlockInstances(
    date: LocalDate,
    activeSession: ActiveBlockSession?,
    logStore: BlockSessionLogStore?
): Pair<List<NamedBlockInstance>, List<NamedBlockInstance>> {
    val zone = ZoneId.systemDefault()
    val floatingSplit = loadAllBlocks()
        .filter { it.isFloating && it.enabled && !isSkippedForDate(it.id, date) }
        .map { block -> NamedBlockInstance(block, 0L, 0L, resolveActiveTasks(block.id, date).withMeasuredLengths(logStore)) }
        .pinnedBySessions(date, activeSession, logStore)
    val fixed = resolveForDate(date).map { (block, sched) ->
        val startMs = date.atTime(sched.startHour, sched.startMinute).atZone(zone).toInstant().toEpochMilli()
        val activeTasks = resolveActiveTasks(block.id, date).withMeasuredLengths(logStore)
        val endMs = if (sched.endHour >= 0) {
            val e = date.atTime(sched.endHour, sched.endMinute).atZone(zone).toInstant().toEpochMilli()
            if (e > startMs) e else e + 24 * 3600_000L
        } else startMs + effectiveDurationMinutes(block, activeTasks) * 60_000L
        NamedBlockInstance(block, startMs, endMs, activeTasks)
    }.reconciledWithActualSessions(date, activeSession, logStore)
    return (fixed + floatingSplit.first) to floatingSplit.second
}

/** [date]'s plan as the timeline has it: blocks as above, and calendar time reserved. */
suspend fun planLiveDay(
    registry: EventPlannerRegistry,
    blocks: NamedBlockStore,
    logStore: BlockSessionLogStore?,
    activeSession: ActiveBlockSession?,
    calendar: CalendarSignals?,
    calendarPrefs: CalendarPrefsStore?,
    date: LocalDate = LocalDate.now()
): DayPlan {
    // Today and the next day, as the timeline fetches them (an event past midnight counts).
    val events = calendar?.takeIf { it.hasPermission() }
        ?.let { runCatching { it.eventsInRange(date, date.plusDays(2)) }.getOrNull() }
        .orEmpty()
    return withContext(Dispatchers.Default) {
        val (fixed, floating) = blocks.liveBlockInstances(date, activeSession, logStore)
        val (calBlocks, reserving) = plannerCalendarInputs(events, calendarPrefs)
        registry.planForDate(
            date,
            calendarEventBlocks = calBlocks,
            reservingBlocks = reserving,
            nowMs = if (date == LocalDate.now()) System.currentTimeMillis() else null,
            namedBlockInstances = fixed,
            floatingBlocks = floating
        )
    }
}
