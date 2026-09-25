package com.waypoint.app.planner

import com.waypoint.app.script.TaskManagerScript
import com.waypoint.app.signal.CalendarEvent
import com.waypoint.app.signal.CalendarSignals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** How far the Plan tab is zoomed out: one day's timeline, or tiles for a week, month or year. */
enum class PlanZoomLevel {
    DAY, WEEK, MONTH, YEAR;

    /** One level further out, or null at Year. */
    val outer: PlanZoomLevel? get() = entries.getOrNull(ordinal + 1)
    /** One level further in, or null at Day. */
    val inner: PlanZoomLevel? get() = entries.getOrNull(ordinal - 1)
}

enum class OverviewKind { BLOCK, TASK, EVENT }

/** One thing on a day, as the overview tiles draw it. */
data class OverviewItem(
    val title: String,
    val colorArgb: Int?,
    val startMs: Long,
    val endMs: Long,
    val kind: OverviewKind,
    val allDay: Boolean = false,
    val id: String = "",
    /** Ticked off (today only: done marks last a wake). */
    val done: Boolean = false,
    /** A block's own tasks (before, during and after it), in time order. */
    val subItems: List<OverviewItem> = emptyList(),
    /** For a block's task: the phase it runs in, if any. */
    val phaseName: String? = null
)

data class DaySummary(val date: LocalDate, val items: List<OverviewItem>)

/** Monday of [date]'s week. */
fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

fun weekDays(date: LocalDate): List<LocalDate> = weekStart(date).let { mon -> (0L..6L).map { mon.plusDays(it) } }

/** The Mondays of every week that has a day in [month], in order. */
fun monthWeekStarts(month: YearMonth): List<LocalDate> {
    val first = weekStart(month.atDay(1))
    val last = month.atEndOfMonth()
    return generateSequence(first) { it.plusWeeks(1) }.takeWhile { !it.isAfter(last) }.toList()
}

/**
 * Builds [DaySummary]s for the overview tiles. With [withPlan] each day is planned the way the
 * day timeline plans it (blocks, auto-placed blocks, floating tasks, calendar time), so tasks
 * show where they'd land; without it (a year's worth of days) only fixed blocks and calendar
 * events are counted, which needs no planning. Block sub-tasks and sleep are left out: the
 * tiles are for seeing the shape of a day, not every step.
 */
class PlanOverviewLoader(
    private val registry: EventPlannerRegistry,
    private val blocks: NamedBlockStore,
    private val calendar: CalendarSignals?,
    private val calendarPrefs: CalendarPrefsStore?,
    /** Whether a task is ticked off; only asked about today's. */
    private val isDone: (String) -> Boolean = { false }
) {
    suspend fun load(dates: List<LocalDate>, withPlan: Boolean): Map<LocalDate, DaySummary> {
        if (dates.isEmpty()) return emptyMap()
        val zone = ZoneId.systemDefault()
        val first = dates.min()
        val last = dates.max()
        val events = calendar?.takeIf { it.hasPermission() }
            ?.let { runCatching { it.eventsInRange(first, last.plusDays(1)) }.getOrNull() }
            .orEmpty()
            .filter { it.title != "Sleep" }
        return withContext(Dispatchers.Default) {
            val blockColors = blocks.loadAllBlocks().associate { it.id to it.colorArgb }
            val today = LocalDate.now()
            dates.associateWith { date ->
                val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEvents = events.filter { e ->
                    if (e.allDay) {
                        // All-day events are stored at UTC midnight: compare dates, not instants.
                        val from = java.time.Instant.ofEpochMilli(e.startMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        val to = java.time.Instant.ofEpochMilli(e.endMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        !date.isBefore(from) && date.isBefore(maxOf(to, from.plusDays(1)))
                    } else e.endMillis > dayStart && e.startMillis < dayEnd
                }
                val eventItems = dayEvents.map { it.toItem() }
                val planItems = if (withPlan) planItems(date, dayEvents, blockColors, today)
                    else blocks.resolveFixedInstancesForDate(date).map { inst ->
                        OverviewItem(inst.block.name, inst.block.colorArgb, inst.scheduledStartMs, inst.estimatedEndMs, OverviewKind.BLOCK)
                    }
                DaySummary(date, (planItems + eventItems).sortedWith(compareBy({ !it.allDay }, { it.startMs })))
            }
        }
    }

    private fun planItems(
        date: LocalDate,
        dayEvents: List<CalendarEvent>,
        blockColors: Map<String, Int?>,
        today: LocalDate
    ): List<OverviewItem> {
        val (calBlocks, reserving) = plannerCalendarInputs(dayEvents, calendarPrefs)
        val fixed = blocks.resolveFixedInstancesForDate(date)
        val floating = blocks.resolveFloatingInstancesForDate(date)
        val plan = registry.planForDate(
            date,
            calendarEventBlocks = calBlocks,
            reservingBlocks = reserving,
            nowMs = if (date == today) System.currentTimeMillis() else null,
            namedBlockInstances = fixed,
            floatingBlocks = floating
        )
        val phaseNames = (fixed + floating).flatMap { inst ->
            inst.activeTasks.mapNotNull { t -> t.phaseOf(inst.block)?.let { t.id to it.name } }
        }.toMap()
        fun done(id: String) = date == today && isDone(id)
        return plan.scheduled.mapNotNull { se ->
            when {
                se.event.category == EventCategory.BLOCK -> {
                    val id = se.event.id.removePrefix("__block__")
                    // The block alone, not its tile stretched over its before/after tasks.
                    val (start, end) = plan.blockBounds[id] ?: (se.startMillis to se.endMillis)
                    val tasks = plan.scheduled
                        .filter { it.event.sourceWidgetId == se.event.id }
                        .sortedBy { it.startMillis }
                        .map { t ->
                            OverviewItem(
                                t.event.title, t.event.colorArgb, t.startMillis, t.endMillis, OverviewKind.TASK,
                                id = t.event.id, done = done(t.event.id), phaseName = phaseNames[t.event.id]
                            )
                        }
                    OverviewItem(se.event.title, blockColors[id], start, end, OverviewKind.BLOCK, id = id, subItems = tasks)
                }
                se.event.sourceWidgetId == TaskManagerScript.WIDGET_ID ->
                    OverviewItem(
                        se.event.title, se.event.colorArgb, se.startMillis, se.endMillis, OverviewKind.TASK,
                        id = se.event.id, done = done(se.event.id)
                    )
                else -> null
            }
        }
    }

    private fun CalendarEvent.toItem() =
        OverviewItem(title, calendarColor, startMillis, endMillis, OverviewKind.EVENT, allDay = allDay)
}
