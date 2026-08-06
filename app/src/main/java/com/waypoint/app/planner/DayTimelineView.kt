package com.waypoint.app.planner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waypoint.app.signal.CalendarEvent
import com.waypoint.app.signal.CalendarSignals
import com.waypoint.app.planner.CalendarPrefsStore
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

// The visible window runs from VIEW_START_HOUR on the selected date to
// VIEW_START_HOUR on the following date (e.g. 4 AM → 4 AM).
// All internal "minute" values are relative to that 4 AM anchor.
private const val VIEW_START_HOUR = 4
private const val START_HOUR = 0          // relative minute 0 = VIEW_START_HOUR
private const val END_HOUR   = 24         // relative minute 1440 = next-day VIEW_START_HOUR
private const val TOTAL_HOURS = END_HOUR - START_HOUR
private val HOUR_HEIGHT = 67.dp
private val LABEL_WIDTH = 44.dp

/** Converts a relative-minute value to a Y offset within the timeline. */
private fun minToY(minutes: Int, hourHeight: Dp): Dp =
    hourHeight * (minutes.coerceAtLeast(0) / 60f)

/** Returns minutes elapsed since the 4 AM view-start anchor. Negative = before the window. */
private fun minutesFromViewStart(viewStartMs: Long): Int =
    ((System.currentTimeMillis() - viewStartMs) / 60_000L).toInt()

/** Converts an absolute timestamp to minutes relative to the 4 AM view-start anchor. */
private fun msToMin(ms: Long, viewStartMs: Long): Int =
    ((ms - viewStartMs) / 60_000L).toInt()

private fun fmtMs(ms: Long): String =
    Calendar.getInstance().apply { timeInMillis = ms }
        .let { "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }

/** Positions a child at an absolute y offset within its parent Box via layout. */
private fun Modifier.yOffset(y: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.placeRelative(0, y.roundToPx())
    }
}

@Composable
fun DayTimelineView(
    registry: EventPlannerRegistry,
    calendarSignals: CalendarSignals? = null,
    calendarPrefsStore: CalendarPrefsStore? = null,
    namedBlockStore: NamedBlockStore? = null,
    date: LocalDate = LocalDate.now(),
    refreshKey: Int = 0,
    modifier: Modifier = Modifier,
    sessionWindow: Pair<Long, Long>? = null,
    activeBlockId: String? = null,
    onBlockStart: ((blockId: String, scheduledEndMs: Long) -> Unit)? = null,
    onCalendarEventClick: ((CalendarEvent) -> Unit)? = null,
    onPlannerEventClick: ((ScheduledEvent) -> Unit)? = null,
    onCalEventsChanged: ((List<CalendarEvent>) -> Unit)? = null
) {
    val isToday = date == LocalDate.now()

    // Compute blockInstances first so we can resolve the scheduled block start for session mode
    val blockInstances = remember(date, refreshKey) {
        namedBlockStore?.resolveForDate(date)?.map { (block, sched) ->
            val startMs = date.atTime(sched.startHour, sched.startMinute)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endMs = if (sched.endHour >= 0) {
                val e = date.atTime(sched.endHour, sched.endMinute)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (e > startMs) e else e + 24 * 3600_000L
            } else startMs + block.estimatedMinutes * 60_000L
            NamedBlockInstance(block, startMs, endMs,
                namedBlockStore.resolveActiveTasks(block.id, date))
        } ?: emptyList()
    }
    // Floating blocks have no fixed schedule; the planner assigns them a slot.
    // Pass with placeholder times (0L) — the planner fills in the real start/end.
    val floatingBlockInstances = remember(date, refreshKey) {
        namedBlockStore?.loadAllBlocks()?.filter { it.isFloating }?.map { block ->
            NamedBlockInstance(block, 0L, 0L, namedBlockStore.resolveActiveTasks(block.id, date))
        } ?: emptyList()
    }

    // In session mode use the block's SCHEDULED start (from blockInstances) so the full
    // block history is visible from 09:00 even if the user tapped Start at 22:00.
    val scheduledBlockStart: Long? = if (sessionWindow != null && activeBlockId != null)
        blockInstances.find { it.block.id == activeBlockId }?.scheduledStartMs else null
    val blockWindowStart: Long? = if (sessionWindow != null) scheduledBlockStart ?: sessionWindow.first else null
    val blockWindowEnd:   Long? = sessionWindow?.second

    // View spans block window ± 2h padding in session mode; standard 4 AM–4 AM otherwise.
    val viewStartMs = remember(date, blockWindowStart) {
        if (blockWindowStart != null) blockWindowStart - 2 * 3600_000L
        else date.atTime(VIEW_START_HOUR, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val viewEndMs = remember(viewStartMs, blockWindowEnd) {
        if (blockWindowEnd != null) blockWindowEnd + 2 * 3600_000L
        else viewStartMs + TOTAL_HOURS * 3600_000L
    }
    val totalMinutes = remember(viewStartMs, viewEndMs) {
        ((viewEndMs - viewStartMs) / 60_000L).toInt().coerceAtLeast(60)
    }
    val totalHours = remember(totalMinutes) { (totalMinutes + 59) / 60 }
    val isNowVisible = System.currentTimeMillis() in viewStartMs until viewEndMs
    val nextDayBlockInstances = remember(date, refreshKey) {
        val nextDate = date.plusDays(1)
        namedBlockStore?.resolveForDate(nextDate)?.map { (block, sched) ->
            val startMs = nextDate.atTime(sched.startHour, sched.startMinute)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endMs = if (sched.endHour >= 0) {
                val e = nextDate.atTime(sched.endHour, sched.endMinute)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (e > startMs) e else e + 24 * 3600_000L
            } else startMs + block.estimatedMinutes * 60_000L
            NamedBlockInstance(block, startMs, endMs,
                namedBlockStore.resolveActiveTasks(block.id, nextDate))
        } ?: emptyList()
    }
    val nextDayFloatingInstances = remember(date, refreshKey) {
        val nextDate = date.plusDays(1)
        namedBlockStore?.loadAllBlocks()?.filter { it.isFloating }?.map { block ->
            NamedBlockInstance(block, 0L, 0L, namedBlockStore.resolveActiveTasks(block.id, nextDate))
        } ?: emptyList()
    }

    var plan by remember(date, refreshKey) {
        mutableStateOf(registry.planForDate(date, namedBlockInstances = blockInstances,
            floatingBlocks = floatingBlockInstances,
            nowMs = if (isToday) System.currentTimeMillis() else null))
    }
    var nextDayScheduled by remember(date, refreshKey) {
        mutableStateOf(registry.planForDate(date.plusDays(1), namedBlockInstances = nextDayBlockInstances,
            floatingBlocks = nextDayFloatingInstances).scheduled)
    }
    var nowMin by remember(viewStartMs) { mutableIntStateOf(minutesFromViewStart(viewStartMs)) }
    var calEvents by remember(date) { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    var calEventBlocks by remember(date, refreshKey) { mutableStateOf<Map<Long, Pair<Long, Long>>>(emptyMap()) }
    var reservingBlocks by remember(date, refreshKey) { mutableStateOf<List<Pair<Long, Long>>>(emptyList()) }

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("waypoint_timeline", android.content.Context.MODE_PRIVATE) }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    var zoomIndex by remember { mutableIntStateOf(1) }
    val zoomFactors = listOf(1f, 2.5f, 5f)
    val zoomLabels  = listOf("1×", "2.5×", "5×")
    val hourHeight  = HOUR_HEIGHT * zoomFactors[zoomIndex]
    val showQuarterLabels = zoomIndex == 1
    val showMinuteLines   = zoomIndex == 2
    var anchorMinute by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        if (prefs.contains("zoom_index")) {
            zoomIndex = prefs.getInt("zoom_index", 1)
        }
    }

    val inSession = sessionWindow != null
    LaunchedEffect(date, inSession) {
        if (inSession) {
            // Session mode: scroll to block start (past the 2h padding) so the user sees
            // the block from the beginning and can scroll up to see pre-block context.
            val paddingPx = with(density) { 2f * hourHeight.toPx() }.toInt()
            scrollState.scrollTo(paddingPx)
        } else {
            val scrollMin = if (isNowVisible) (nowMin - 60) else (4 * 60)
            val scrollPx = with(density) {
                scrollMin.coerceAtLeast(0) / 60f * hourHeight.toPx()
            }.toInt()
            scrollState.animateScrollTo(scrollPx)
        }
    }

    LaunchedEffect(zoomIndex) {
        prefs.edit().putInt("zoom_index", zoomIndex).apply()
        if (anchorMinute >= 0) {
            val scrollPx = with(density) {
                (anchorMinute.coerceAtLeast(0) / 60f * hourHeight.toPx()).toInt()
            }
            scrollState.scrollTo(scrollPx)
        }
    }

    LaunchedEffect(date, refreshKey) {
        suspend fun fetchAndSync() {
            if (calendarSignals?.hasPermission() == true) {
                val today   = calendarSignals.eventsForDate(date)
                val nextDay = calendarSignals.eventsForDate(date.plusDays(1))
                val combined = today + nextDay
                calEvents = combined
                val allCalBlocks = combined
                    .filter { !it.allDay && it.title != "Sleep" }
                    .associate { it.eventId to (it.startMillis to it.endMillis) }
                calEventBlocks = allCalBlocks
                val blocks = combined
                    .filter { evt ->
                        !evt.allDay && evt.title != "Sleep" &&
                            (calendarPrefsStore == null || calendarPrefsStore.reservesTime(evt.eventId))
                    }
                    .map { it.startMillis to it.endMillis }
                reservingBlocks = blocks
                plan = registry.planForDate(date, calendarEventBlocks = allCalBlocks,
                    reservingBlocks = blocks, namedBlockInstances = blockInstances,
                    floatingBlocks = floatingBlockInstances,
                    nowMs = if (isToday) System.currentTimeMillis() else null)
                nextDayScheduled = registry.planForDate(date.plusDays(1),
                    calendarEventBlocks = allCalBlocks, reservingBlocks = blocks,
                    namedBlockInstances = nextDayBlockInstances,
                    floatingBlocks = nextDayFloatingInstances).scheduled
                onCalEventsChanged?.invoke(combined)
            }
        }
        fetchAndSync()
        while (true) { delay(60_000L); fetchAndSync() }
    }

    LaunchedEffect(viewStartMs) {
        if (isNowVisible) {
            while (true) {
                delay(60_000L)
                nowMin = minutesFromViewStart(viewStartMs)
                val allCalBlocks = calEventBlocks
                val blocks = reservingBlocks
                plan = registry.planForDate(date, calendarEventBlocks = allCalBlocks,
                    reservingBlocks = blocks, namedBlockInstances = blockInstances,
                    floatingBlocks = floatingBlockInstances,
                    nowMs = if (isToday) System.currentTimeMillis() else null)
                nextDayScheduled = registry.planForDate(date.plusDays(1),
                    calendarEventBlocks = allCalBlocks, reservingBlocks = blocks,
                    namedBlockInstances = nextDayBlockInstances,
                    floatingBlocks = nextDayFloatingInstances).scheduled
            }
        }
    }

    val outline        = MaterialTheme.colorScheme.outlineVariant
    val surface        = MaterialTheme.colorScheme.surface
    val onSV           = MaterialTheme.colorScheme.onSurfaceVariant
    val secCont        = MaterialTheme.colorScheme.secondaryContainer
    val terCont        = MaterialTheme.colorScheme.tertiaryContainer
    val onSecCont      = MaterialTheme.colorScheme.onSecondaryContainer
    val onTerCont      = MaterialTheme.colorScheme.onTertiaryContainer
    val indicatorColor = if (sessionWindow != null) MaterialTheme.colorScheme.primary else Color(0xFFE53935)

    val totalH = hourHeight * totalHours

    Box(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            Row(Modifier.fillMaxWidth().height(totalH)) {
                HourLabelsColumn(
                    viewStartMs = viewStartMs,
                    totalHours = totalHours,
                    hourHeight = hourHeight,
                    showQuarterLabels = showQuarterLabels,
                    showMinuteLines = showMinuteLines,
                    onSV = onSV
                )
                TimelineBody(
                    modifier = Modifier.weight(1f),
                    viewStartMs = viewStartMs,
                    viewEndMs = viewEndMs,
                    totalHours = totalHours,
                    totalMinutes = totalMinutes,
                    hourHeight = hourHeight,
                    showMinuteLines = showMinuteLines,
                    plan = plan,
                    nextDayScheduled = nextDayScheduled,
                    calEvents = calEvents,
                    blockInstances = blockInstances,
                    nextDayBlockInstances = nextDayBlockInstances,
                    nowMin = nowMin,
                    isNowVisible = isNowVisible,
                    isToday = isToday,
                    inSession = sessionWindow != null,
                    blockWindowStart = blockWindowStart,
                    blockWindowEnd = blockWindowEnd,
                    activeBlockId = activeBlockId,
                    onBlockStart = onBlockStart,
                    outline = outline,
                    onSV = onSV,
                    secCont = secCont,
                    terCont = terCont,
                    onSecCont = onSecCont,
                    onTerCont = onTerCont,
                    indicatorColor = indicatorColor,
                    onCalendarEventClick = onCalendarEventClick,
                    onPlannerEventClick = onPlannerEventClick
                )
            }
        }
        // Zoom level pill — fixed overlay, does not scroll with the timeline
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 4.dp, end = 6.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(surface.copy(alpha = 0.88f))
                .border(1.dp, outline, RoundedCornerShape(6.dp))
                .clickable {
                    val topMinute = (scrollState.value / with(density) { hourHeight.toPx() } * 60).toInt()
                    anchorMinute = topMinute
                    zoomIndex = (zoomIndex + 1) % zoomFactors.size
                }
                .padding(horizontal = 9.dp, vertical = 3.dp)
        ) {
            Text(
                text = zoomLabels[zoomIndex],
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = onSV
            )
        }
    }
}

// ── Hour label column ─────────────────────────────────────────────────────────

@Composable
private fun HourLabelsColumn(
    viewStartMs: Long,
    totalHours: Int,
    hourHeight: Dp,
    showQuarterLabels: Boolean,
    showMinuteLines: Boolean,
    onSV: Color
) {
    Box(Modifier.width(LABEL_WIDTH).fillMaxHeight()) {
        for (h in 0..totalHours) {
            val yOff = (hourHeight * h - 8.dp).coerceAtLeast(2.dp)
            val wallHour = Calendar.getInstance().apply {
                timeInMillis = viewStartMs + h * 3600_000L
            }.get(Calendar.HOUR_OF_DAY)
            Text(
                text = "%02d:00".format(wallHour),
                modifier = Modifier.yOffset(yOff),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = onSV.copy(alpha = 0.38f)
            )
            if (showQuarterLabels && h < totalHours) {
                for (m in listOf(15, 30, 45)) {
                    val minYOff = (hourHeight * h + hourHeight * m / 60f - 6.dp).coerceAtLeast(2.dp)
                    Text(
                        text = ":%02d".format(m),
                        modifier = Modifier.yOffset(minYOff).padding(start = 4.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = onSV.copy(alpha = 0.38f)
                    )
                }
            }
            if (showMinuteLines && h < totalHours) {
                for (m in 5..55 step 5) {
                    val minYOff = (hourHeight * h + hourHeight * m / 60f - 6.dp).coerceAtLeast(2.dp)
                    val alpha = if (m % 15 == 0) 0.45f else 0.30f
                    Text(
                        text = ":%02d".format(m),
                        modifier = Modifier.yOffset(minYOff).padding(start = 4.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = onSV.copy(alpha = alpha)
                    )
                }
            }
        }
    }
}

// ── Timeline body ─────────────────────────────────────────────────────────────

@Composable
private fun TimelineBody(
    modifier: Modifier = Modifier,
    viewStartMs: Long,
    viewEndMs: Long,
    totalHours: Int,
    totalMinutes: Int,
    hourHeight: Dp,
    showMinuteLines: Boolean,
    plan: DayPlan,
    nextDayScheduled: List<ScheduledEvent>,
    calEvents: List<CalendarEvent>,
    blockInstances: List<NamedBlockInstance>,
    nextDayBlockInstances: List<NamedBlockInstance>,
    nowMin: Int,
    isNowVisible: Boolean,
    isToday: Boolean,
    inSession: Boolean,
    blockWindowStart: Long?,
    blockWindowEnd: Long?,
    activeBlockId: String?,
    onBlockStart: ((blockId: String, scheduledEndMs: Long) -> Unit)?,
    outline: Color,
    onSV: Color,
    secCont: Color,
    terCont: Color,
    onSecCont: Color,
    onTerCont: Color,
    indicatorColor: Color,
    onCalendarEventClick: ((CalendarEvent) -> Unit)?,
    onPlannerEventClick: ((ScheduledEvent) -> Unit)?
) {
    val viewTotalMin = totalMinutes

    // Merge today's events with next-day events inside the 4AM-4AM window
    val mergedScheduled = run {
        val combined = (plan.scheduled + nextDayScheduled.filter { it.startMillis < viewEndMs })
            .sortedBy { it.startMillis }
        val out = mutableListOf<ScheduledEvent>()
        for (se in combined) {
            val last = out.lastOrNull()
            if (last != null && last.endMillis == se.startMillis &&
                (last.event.id == se.event.id ||
                 (last.event.category == EventCategory.SLEEP && se.event.category == EventCategory.SLEEP))
            ) {
                out[out.size - 1] = last.copy(endMillis = se.endMillis)
            } else {
                out += se
            }
        }
        out
    }

    // In session mode: remove the active block tile (drawn as background fill instead) and
    // restrict other events to the block window.
    val visibleScheduled = if (inSession) {
        val winStart = blockWindowStart ?: viewStartMs
        val winEnd   = blockWindowEnd   ?: viewEndMs
        mergedScheduled.filter { se ->
            if (se.event.category == EventCategory.BLOCK &&
                se.event.id.removePrefix("__block__") == activeBlockId) return@filter false
            se.startMillis < winEnd && se.endMillis > winStart
        }
    } else mergedScheduled

    // Calendar events filtered to block window when in session (padding zone stays empty)
    val visibleCalEvents = if (inSession && blockWindowStart != null && blockWindowEnd != null) {
        calEvents.filter { it.endMillis > blockWindowStart && it.startMillis < blockWindowEnd }
    } else calEvents

    // Free window range: restricted to the actual block window in session mode
    val freeRangeStart = if (inSession && blockWindowStart != null)
        msToMin(blockWindowStart, viewStartMs).coerceAtLeast(0) else 0
    val freeRangeEnd = if (inSession && blockWindowEnd != null)
        msToMin(blockWindowEnd, viewStartMs).coerceAtMost(viewTotalMin) else viewTotalMin

    // Compute free time windows (gaps ≥ 15 min between occupied ranges)
    val freeWindows = run {
        val raw = mutableListOf<Pair<Int, Int>>()
        visibleScheduled.forEach { se ->
            val s = msToMin(se.startMillis, viewStartMs)
            val e = msToMin(se.endMillis,   viewStartMs)
            if (e > s) raw += s to e
        }
        visibleCalEvents.filter { !it.allDay }.forEach { evt ->
            val s = msToMin(evt.startMillis, viewStartMs)
            val e = msToMin(evt.endMillis,   viewStartMs)
            if (e > s) raw += s to e
        }
        val sorted = raw.sortedBy { it.first }
        val merged = mutableListOf<Pair<Int, Int>>()
        for ((s, e) in sorted) {
            val last = merged.lastOrNull()
            if (last != null && s <= last.second)
                merged[merged.size - 1] = last.first to maxOf(last.second, e)
            else merged += s to e
        }
        val windows = mutableListOf<Pair<Int, Int>>()
        var cursor = freeRangeStart
        for ((occStart, occEnd) in merged) {
            if (occStart >= freeRangeEnd) break
            val gapEnd = occStart.coerceIn(freeRangeStart, freeRangeEnd)
            if (gapEnd > cursor && gapEnd - cursor >= 15) windows += cursor to gapEnd
            if (occEnd > cursor) cursor = occEnd
        }
        if (freeRangeEnd > cursor && freeRangeEnd - cursor >= 15) windows += cursor to freeRangeEnd
        windows
    }

    Box(modifier.fillMaxHeight().clipToBounds()) {
        // Block window background: colored fill + thick boundary lines at start/end.
        // Drawn first so it sits beneath the grid and events.
        if (inSession && blockWindowStart != null && blockWindowEnd != null) {
            val blockColor = blockInstances.find { it.block.id == activeBlockId }
                ?.block?.colorArgb?.let { Color(it) }
                ?: MaterialTheme.colorScheme.primary
            val bStartMin = msToMin(blockWindowStart, viewStartMs).coerceAtLeast(0)
            val bEndMin   = msToMin(blockWindowEnd,   viewStartMs).coerceAtMost(viewTotalMin)
            val bStartY   = minToY(bStartMin, hourHeight)
            val bEndY     = minToY(bEndMin,   hourHeight)
            val fillH     = (bEndY - bStartY).coerceAtLeast(0.dp)
            Box(
                Modifier
                    .yOffset(bStartY)
                    .fillMaxWidth()
                    .height(fillH)
                    .background(blockColor.copy(alpha = 0.12f))
            )
            Canvas(Modifier.fillMaxWidth().yOffset(bStartY).height(fillH)) {
                drawLine(blockColor, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 3.dp.toPx())
                drawLine(blockColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 3.dp.toPx())
            }
        }

        GridLines(hourHeight = hourHeight, totalHours = totalHours, showMinuteLines = showMinuteLines, outline = outline)

        // Free time windows
        freeWindows.forEach { (startMin, endMin) ->
            FreeWindowBlock(startMin, endMin, hourHeight, onSV)
        }

        // Calendar event blocks (skip Sleep — handled by planner)
        visibleCalEvents.filter { !it.allDay && it.title != "Sleep" }.forEach { evt ->
            CalendarEventBlock(evt, viewStartMs, viewTotalMin, hourHeight, onCalendarEventClick)
        }

        // Planner event blocks
        val eventColors = listOf(secCont to onSecCont, terCont to onTerCont)
        var habitIdx = 0
        visibleScheduled.forEach { se ->
            val colorPair = if (se.event.category == EventCategory.SLEEP ||
                                se.event.category == EventCategory.BLOCK) null
                            else eventColors[habitIdx++ % eventColors.size]
            PlannerEventBlock(
                se = se,
                viewStartMs = viewStartMs,
                hourHeight = hourHeight,
                blockInstances = blockInstances,
                nextDayBlockInstances = nextDayBlockInstances,
                defaultColorPair = colorPair,
                isToday = isToday,
                activeBlockId = activeBlockId,
                onBlockStart = onBlockStart,
                onPlannerEventClick = onPlannerEventClick
            )
        }

        // Alarm markers for planned sleep
        AlarmMarkersSection(
            mergedScheduled = visibleScheduled,
            viewStartMs = viewStartMs,
            totalMinutes = totalMinutes,
            hourHeight = hourHeight
        )

        // Current-time indicator
        if (isNowVisible) {
            val clampedNow = nowMin.coerceIn(0, totalMinutes)
            val nowY = minToY(clampedNow, hourHeight)
            val nowLabel = remember(nowMin) {
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(viewStartMs + clampedNow * 60_000L))
            }
            Text(
                text = nowLabel,
                color = indicatorColor,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                modifier = Modifier
                    .yOffset(nowY - 20.dp)
                    .padding(start = 2.dp)
            )
            Canvas(Modifier.yOffset(nowY - 4.dp).fillMaxWidth().height(8.dp)) {
                val cy = size.height / 2f
                drawCircle(indicatorColor, 4.dp.toPx(), Offset(0f, cy))
                drawLine(indicatorColor, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1.5.dp.toPx())
            }
        }
    }
}

// ── Per-element composables ────────────────────────────────────────────────────

@Composable
private fun GridLines(hourHeight: Dp, totalHours: Int, showMinuteLines: Boolean, outline: Color) {
    Canvas(Modifier.fillMaxSize()) {
        for (h in 0..totalHours) {
            val y = h * hourHeight.toPx()
            drawLine(color = outline, start = Offset(0f, y), end = Offset(size.width, y),
                strokeWidth = 0.5.dp.toPx())
            if (h < totalHours) {
                if (showMinuteLines) {
                    for (m in 1..59) {
                        if (m % 15 == 0) continue
                        val my = y + m * hourHeight.toPx() / 60f
                        drawLine(color = outline.copy(alpha = 0.38f),
                            start = Offset(0f, my), end = Offset(size.width, my),
                            strokeWidth = 0.4.dp.toPx())
                    }
                }
                for (q in 1..3) {
                    val qy = y + q * hourHeight.toPx() / 4f
                    drawLine(color = outline.copy(alpha = 0.65f),
                        start = Offset(0f, qy), end = Offset(size.width, qy),
                        strokeWidth = 0.45.dp.toPx())
                }
            }
        }
    }
}

@Composable
private fun FreeWindowBlock(startMin: Int, endMin: Int, hourHeight: Dp, onSV: Color) {
    val startY = minToY(startMin, hourHeight)
    val blockH = (minToY(endMin, hourHeight) - startY).coerceAtLeast(4.dp)
    val durMin = endMin - startMin
    val h = durMin / 60; val m = durMin % 60
    val durLabel = when {
        durMin >= 60 && m > 0 -> "${h}h ${m}m"
        durMin >= 60           -> "${h}h"
        else                   -> "${durMin}m"
    }
    Box(
        Modifier
            .yOffset(startY)
            .fillMaxWidth()
            .height(blockH)
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(onSV.copy(alpha = 0.03f))
            .border(1.dp, onSV.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
    ) {
        if (blockH >= 20.dp) {
            Text(
                text = "free · $durLabel",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = onSV.copy(alpha = 0.30f)
            )
        }
    }
}

@Composable
private fun CalendarEventBlock(
    evt: CalendarEvent,
    viewStartMs: Long,
    viewTotalMin: Int,
    hourHeight: Dp,
    onCalendarEventClick: ((CalendarEvent) -> Unit)?
) {
    val ceStartMin = msToMin(evt.startMillis, viewStartMs)
    val ceEndMin   = msToMin(evt.endMillis,   viewStartMs)
    if (ceStartMin >= viewTotalMin || ceEndMin <= 0) return
    val startY  = minToY(ceStartMin, hourHeight)
    val eventH  = (minToY(ceEndMin, hourHeight) - startY - 2.dp).coerceAtLeast(24.dp)
    val calColor = if (evt.calendarColor != 0) Color(evt.calendarColor)
                   else MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .yOffset(startY + 1.dp)
            .fillMaxWidth()
            .height(eventH)
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(if (onCalendarEventClick != null) Modifier.clickable { onCalendarEventClick(evt) } else Modifier)
            .background(calColor.copy(alpha = 0.13f))
            .border(1.dp, calColor.copy(alpha = 0.38f), RoundedCornerShape(6.dp))
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                evt.title,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                fontWeight = FontWeight.SemiBold,
                color = calColor.copy(alpha = 0.85f),
                maxLines = 1
            )
            if (eventH >= 36.dp) {
                Text(
                    "${fmtMs(evt.startMillis)} – ${fmtMs(evt.endMillis)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = calColor.copy(alpha = 0.55f)
                )
            }
        }
    }
}

@Composable
private fun PlannerEventBlock(
    se: ScheduledEvent,
    viewStartMs: Long,
    hourHeight: Dp,
    blockInstances: List<NamedBlockInstance>,
    nextDayBlockInstances: List<NamedBlockInstance>,
    defaultColorPair: Pair<Color, Color>?,
    isToday: Boolean,
    activeBlockId: String?,
    onBlockStart: ((blockId: String, scheduledEndMs: Long) -> Unit)?,
    onPlannerEventClick: ((ScheduledEvent) -> Unit)?
) {
    val isSleep       = se.event.category == EventCategory.SLEEP
    val isLoggedSleep = isSleep && se.event.isLogged
    val isBlock       = se.event.category == EventCategory.BLOCK
    val seStartMin = msToMin(se.startMillis, viewStartMs)
    val seEndMin   = msToMin(se.endMillis,   viewStartMs)
    val startY = minToY(seStartMin, hourHeight)
    val eventH = (minToY(seEndMin, hourHeight) - startY - 2.dp).coerceAtLeast(24.dp)

    val sleepAccent = Color(0xFF6B8ABD)
    val blockAccent: Color? = if (isBlock) {
        val blockId = se.event.id.removePrefix("__block__")
        val stored = blockInstances.find { it.block.id == blockId }
            ?: nextDayBlockInstances.find { it.block.id == blockId }
        stored?.block?.colorArgb?.let { Color(it) } ?: Color(0xFF4DB6AC)
    } else null

    val (bg, fg) = when {
        isLoggedSleep -> sleepAccent.copy(alpha = 0.18f) to sleepAccent
        isSleep       -> sleepAccent.copy(alpha = 0.07f) to sleepAccent.copy(alpha = 0.50f)
        isBlock       -> blockAccent!!.copy(alpha = 0.15f) to blockAccent
        else          -> defaultColorPair ?: (Color(0xFF4DB6AC).copy(alpha = 0.15f) to Color(0xFF4DB6AC))
    }
    val displayTitle = when {
        isLoggedSleep -> se.event.title
        isSleep       -> "${se.event.title} · planned"
        else          -> se.event.title
    }
    val borderMod = when {
        isLoggedSleep -> Modifier.border(1.dp, sleepAccent.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
        isSleep       -> Modifier.border(1.dp, sleepAccent.copy(alpha = 0.22f), RoundedCornerShape(6.dp))
        isBlock       -> Modifier.border(1.5.dp, blockAccent!!.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
        else          -> Modifier
    }

    val rawBlockId = if (isBlock) se.event.id.removePrefix("__block__") else null
    val showStartButton = isBlock && isToday && onBlockStart != null && rawBlockId != activeBlockId

    Box(
        Modifier
            .yOffset(startY + 1.dp)
            .fillMaxWidth()
            .height(eventH)
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(if (onPlannerEventClick != null) Modifier.clickable { onPlannerEventClick(se) } else Modifier)
            .background(bg)
            .then(borderMod)
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                displayTitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                fontWeight = if (isLoggedSleep) FontWeight.SemiBold else FontWeight.Normal,
                color = fg,
                maxLines = 1
            )
            if (eventH >= 36.dp) {
                Text(
                    "${fmtMs(se.startMillis)} – ${fmtMs(se.endMillis)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = fg.copy(alpha = 0.65f)
                )
            }
        }
        if (showStartButton) {
            Box(Modifier.align(Alignment.TopEnd).padding(2.dp)) {
                androidx.compose.material3.TextButton(
                    onClick = { onBlockStart!!(rawBlockId!!, se.endMillis) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "▶ Start",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = fg
                    )
                }
            }
        }
    }
}

@Composable
private fun AlarmMarkersSection(
    mergedScheduled: List<ScheduledEvent>,
    viewStartMs: Long,
    totalMinutes: Int,
    hourHeight: Dp
) {
    val sleepBlock = mergedScheduled.firstOrNull {
        it.event.category == EventCategory.SLEEP && !it.event.isLogged
    } ?: return

    val alarmAccent = Color(0xFFF59E0B)
    val alarmPoints = listOf(
        sleepBlock.startMillis - 30 * 60_000L to "Pre-sleep",
        sleepBlock.endMillis   - 15 * 60_000L to "Gentle",
        sleepBlock.endMillis   - 10 * 60_000L to "Alarm",
        sleepBlock.endMillis                   to "Ring",
    )
    alarmPoints.forEach { (alarmMs, label) ->
        val alarmMin = msToMin(alarmMs, viewStartMs)
        if (alarmMin !in 0..totalMinutes) return@forEach
        val alarmY = minToY(alarmMin, hourHeight)
        Canvas(Modifier.yOffset(alarmY).fillMaxWidth().height(1.dp)) {
            drawLine(
                color = alarmAccent.copy(alpha = 0.5f),
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 3.dp.toPx()))
            )
        }
        Row(
            Modifier.yOffset(alarmY - 8.dp).fillMaxWidth().padding(end = 6.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(alarmAccent.copy(alpha = 0.12f))
                    .border(1.dp, alarmAccent.copy(alpha = 0.28f), RoundedCornerShape(4.dp))
            ) {
                Text(
                    "$label · ${fmtMs(alarmMs)}",
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = alarmAccent.copy(alpha = 0.9f)
                )
            }
        }
    }
}
