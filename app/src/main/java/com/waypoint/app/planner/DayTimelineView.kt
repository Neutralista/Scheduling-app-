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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.layout
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

@Composable
fun DayTimelineView(
    registry: EventPlannerRegistry,
    calendarSignals: CalendarSignals? = null,
    calendarPrefsStore: CalendarPrefsStore? = null,
    namedBlockStore: NamedBlockStore? = null,
    date: LocalDate = LocalDate.now(),
    refreshKey: Int = 0,
    modifier: Modifier = Modifier,
    onCalendarEventClick: ((CalendarEvent) -> Unit)? = null,
    onPlannerEventClick: ((ScheduledEvent) -> Unit)? = null,
    onCalEventsChanged: ((List<CalendarEvent>) -> Unit)? = null
) {
    // Anchor: 4 AM on the viewed date in ms. All "minute" values are relative to this.
    val viewStartMs = remember(date) {
        date.atTime(VIEW_START_HOUR, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
    }

    val viewEndMs = viewStartMs + TOTAL_HOURS * 3600_000L
    val isNowVisible = System.currentTimeMillis() in viewStartMs until viewEndMs

    val isToday = date == LocalDate.now()

    // Resolve named block instances for this date and the next (for cross-midnight rendering).
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

    var plan by remember(date, refreshKey) {
        mutableStateOf(registry.planForDate(date, namedBlockInstances = blockInstances,
            nowMs = if (isToday) System.currentTimeMillis() else null))
    }
    // Next-day plan provides the post-midnight half of cross-midnight events inside the 4AM-4AM window.
    // Tomorrow's plan is never trimmed to now — it should show the full intended schedule.
    var nextDayScheduled by remember(date, refreshKey) {
        mutableStateOf(registry.planForDate(date.plusDays(1), namedBlockInstances = nextDayBlockInstances).scheduled)
    }
    var nowMin by remember(viewStartMs) { mutableIntStateOf(minutesFromViewStart(viewStartMs)) }
    var calEvents by remember(date) { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    // All non-allDay calendar events keyed by eventId — for condition resolution (BeforeCalEvent etc.)
    var calEventBlocks by remember(date, refreshKey) { mutableStateOf<Map<Long, Pair<Long, Long>>>(emptyMap()) }
    // Calendar events that the user marked as "reserves time" — fed into the planner as fixed blocks
    var reservingBlocks by remember(date, refreshKey) { mutableStateOf<List<Pair<Long, Long>>>(emptyList()) }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    // Scroll to a sensible position when the date changes
    LaunchedEffect(date) {
        val scrollMin = if (isNowVisible) (nowMin - 60) else (4 * 60)
        val scrollPx = with(density) {
            scrollMin.coerceAtLeast(0) / 60f * HOUR_HEIGHT.toPx()
        }.toInt()
        scrollState.animateScrollTo(scrollPx)
    }

    // Calendar fetch: runs immediately, then every minute; also re-runs on refreshKey change.
    // After fetching, computes which events reserve scheduling time and re-runs the planner.
    LaunchedEffect(date, refreshKey) {
        suspend fun fetchAndSync() {
            if (calendarSignals?.hasPermission() == true) {
                val today = calendarSignals.eventsForDate(date)
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
                plan = registry.planForDate(date, calendarEventBlocks = allCalBlocks, reservingBlocks = blocks,
                    namedBlockInstances = blockInstances,
                    nowMs = if (isToday) System.currentTimeMillis() else null)
                nextDayScheduled = registry.planForDate(date.plusDays(1), calendarEventBlocks = allCalBlocks,
                    reservingBlocks = blocks, namedBlockInstances = nextDayBlockInstances).scheduled
                onCalEventsChanged?.invoke(combined)
            }
        }
        fetchAndSync()
        while (true) {
            delay(60_000L)
            fetchAndSync()
        }
    }

    // 1-minute ticker: update now-line and re-evaluate plan (when now falls in this window)
    LaunchedEffect(viewStartMs) {
        if (isNowVisible) {
            while (true) {
                delay(60_000L)
                nowMin = minutesFromViewStart(viewStartMs)
                val allCalBlocks = calEventBlocks
                val blocks = reservingBlocks
                plan = registry.planForDate(date, calendarEventBlocks = allCalBlocks, reservingBlocks = blocks,
                    namedBlockInstances = blockInstances,
                    nowMs = if (isToday) System.currentTimeMillis() else null)
                nextDayScheduled = registry.planForDate(date.plusDays(1), calendarEventBlocks = allCalBlocks,
                    reservingBlocks = blocks, namedBlockInstances = nextDayBlockInstances).scheduled
            }
        }
    }

    val primary    = MaterialTheme.colorScheme.primary
    val outline    = MaterialTheme.colorScheme.outlineVariant
    val onSV       = MaterialTheme.colorScheme.onSurfaceVariant
    val secCont    = MaterialTheme.colorScheme.secondaryContainer
    val terCont    = MaterialTheme.colorScheme.tertiaryContainer
    val onSecCont  = MaterialTheme.colorScheme.onSecondaryContainer
    val onTerCont  = MaterialTheme.colorScheme.onTertiaryContainer

    fun minToY(minutes: Int): Dp =
        HOUR_HEIGHT * ((minutes - START_HOUR * 60).coerceIn(0, TOTAL_HOURS * 60) / 60f)

    val totalH = HOUR_HEIGHT * TOTAL_HOURS

    // Vertical scroll container — fills whatever the parent gives (weight(1f))
    Box(modifier.fillMaxWidth().verticalScroll(scrollState)) {
        Row(Modifier.fillMaxWidth().height(totalH)) {

            // ── Hour labels column ─────────────────────────────────────────────
            Box(Modifier.width(LABEL_WIDTH).fillMaxHeight()) {
                for (h in START_HOUR..END_HOUR) {
                    val yOff = (HOUR_HEIGHT * (h - START_HOUR) - 8.dp).coerceAtLeast(2.dp)
                    Text(
                        text = "%02d:00".format((VIEW_START_HOUR + h) % 24),
                        modifier = Modifier.yOffset(yOff),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = onSV.copy(alpha = 0.38f)
                    )
                }
            }

            // ── Timeline events column ─────────────────────────────────────────
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clipToBounds()
            ) {
                // Hour and quarter-hour grid lines
                val outlineC = outline
                Canvas(Modifier.fillMaxSize()) {
                    for (h in 0..TOTAL_HOURS) {
                        val y = h * HOUR_HEIGHT.toPx()
                        drawLine(
                            color = outlineC,
                            start = Offset(0f, y), end = Offset(size.width, y),
                            strokeWidth = 0.5.dp.toPx()
                        )
                        if (h < TOTAL_HOURS) {
                            for (q in 1..3) {
                                val qy = y + q * HOUR_HEIGHT.toPx() / 4f
                                drawLine(
                                    color = outlineC.copy(alpha = 0.35f),
                                    start = Offset(0f, qy), end = Offset(size.width, qy),
                                    strokeWidth = 0.3.dp.toPx()
                                )
                            }
                        }
                    }
                }

                // Merge today's events with next-day events that fall inside the 4AM-4AM window,
                // stitching adjacent blocks of the same logical event into one continuous bar.
                val mergedScheduled = run {
                    val combined = (plan.scheduled +
                        nextDayScheduled.filter { it.startMillis < viewEndMs })
                        .sortedBy { it.startMillis }
                    val out = mutableListOf<ScheduledEvent>()
                    for (se in combined) {
                        val last = out.lastOrNull()
                        if (last != null && last.endMillis == se.startMillis &&
                            (last.event.id == se.event.id ||
                             (last.event.category == EventCategory.SLEEP &&
                              se.event.category == EventCategory.SLEEP))
                        ) {
                            out[out.size - 1] = last.copy(endMillis = se.endMillis)
                        } else {
                            out += se
                        }
                    }
                    out
                }

                // ── Free time windows ────────────────────────────────────────────────
                // Compute gaps ≥ 15 min between the shift block and all scheduled events.
                val viewTotalMin = TOTAL_HOURS * 60
                val occupiedRanges = run {
                    val raw = mutableListOf<Pair<Int, Int>>()
                    mergedScheduled.forEach { se ->
                        val s = msToMin(se.startMillis, viewStartMs)
                        val e = msToMin(se.endMillis,   viewStartMs)
                        if (e > s) raw += s to e
                    }
                    calEvents.filter { !it.allDay }.forEach { evt ->
                        val s = msToMin(evt.startMillis, viewStartMs)
                        val e = msToMin(evt.endMillis,   viewStartMs)
                        if (e > s) raw += s to e
                    }
                    // Merge overlapping / adjacent intervals
                    val sorted = raw.sortedBy { it.first }
                    val merged = mutableListOf<Pair<Int, Int>>()
                    for ((s, e) in sorted) {
                        val last = merged.lastOrNull()
                        if (last != null && s <= last.second)
                            merged[merged.size - 1] = last.first to maxOf(last.second, e)
                        else merged += s to e
                    }
                    merged
                }
                val freeWindows = mutableListOf<Pair<Int, Int>>()
                var fwCursor = 0
                for ((occStart, occEnd) in occupiedRanges) {
                    val gapStart = fwCursor.coerceAtLeast(0)
                    val gapEnd   = occStart.coerceAtMost(viewTotalMin)
                    if (gapEnd > gapStart && gapEnd - gapStart >= 15) freeWindows += gapStart to gapEnd
                    if (occEnd > fwCursor) fwCursor = occEnd
                }
                run {
                    val gapStart = fwCursor.coerceAtLeast(0)
                    if (viewTotalMin > gapStart && viewTotalMin - gapStart >= 15)
                        freeWindows += gapStart to viewTotalMin
                }
                freeWindows.forEach { (startMin, endMin) ->
                    val startY = minToY(startMin)
                    val blockH = (minToY(endMin) - startY).coerceAtLeast(4.dp)
                    val durMin = endMin - startMin
                    val durLabel = if (durMin >= 60) {
                        val h = durMin / 60; val m = durMin % 60
                        if (m > 0) "${h}h ${m}m" else "${h}h"
                    } else "${durMin}m"
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

                // Calendar event blocks — rendered after free windows so they appear on top.
                // Skip "Sleep" events: the planner registry already renders them, and orphan
                // sleep calendar events should not appear as a duplicate block.
                calEvents.filter { !it.allDay && it.title != "Sleep" }.forEach { evt ->
                    val ceStartMin = msToMin(evt.startMillis, viewStartMs)
                    val ceEndMin   = msToMin(evt.endMillis,   viewStartMs)
                    if (ceStartMin >= END_HOUR * 60 || ceEndMin <= START_HOUR * 60) return@forEach
                    val startY  = minToY(ceStartMin)
                    val eventH  = (minToY(ceEndMin) - startY - 2.dp).coerceAtLeast(24.dp)
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
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
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

                // Planner event blocks
                val eventColors = listOf(secCont to onSecCont, terCont to onTerCont)
                val sleepAccent = Color(0xFF6B8ABD)
                var habitIdx = 0
                mergedScheduled.forEach { se ->
                    val isSleep = se.event.category == EventCategory.SLEEP
                    val isLoggedSleep = isSleep && se.event.isLogged
                    val isBlock = se.event.category == EventCategory.BLOCK
                    val seStartMin = msToMin(se.startMillis, viewStartMs)
                    val seEndMin   = msToMin(se.endMillis,   viewStartMs)
                    val startY  = minToY(seStartMin)
                    val eventH  = (minToY(seEndMin) - startY - 2.dp).coerceAtLeast(24.dp)
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
                        else          -> eventColors[habitIdx++ % eventColors.size]
                    }
                    val displayTitle = when {
                        isLoggedSleep -> se.event.title
                        isSleep       -> "${se.event.title} · planned"
                        else          -> se.event.title
                    }

                    Box(
                        Modifier
                            .yOffset(startY + 1.dp)
                            .fillMaxWidth()
                            .height(eventH)
                            .padding(horizontal = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .then(if (onPlannerEventClick != null) Modifier.clickable { onPlannerEventClick(se) } else Modifier)
                            .background(bg)
                            .then(
                                when {
                                    isLoggedSleep -> Modifier.border(1.dp, sleepAccent.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                    isSleep       -> Modifier.border(1.dp, sleepAccent.copy(alpha = 0.22f), RoundedCornerShape(6.dp))
                                    isBlock       -> Modifier.border(1.5.dp, blockAccent!!.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                                    else          -> Modifier
                                }
                            )
                    ) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
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
                    }
                }

                // Alarm markers — only for planned (non-logged) sleep, since logged sleep already happened
                val alarmAccent = Color(0xFFF59E0B)
                val sleepBlock = mergedScheduled.firstOrNull { it.event.category == EventCategory.SLEEP && !it.event.isLogged }
                if (sleepBlock != null) {
                    // (epochMs, label or null for minor ticks)
                    val alarmPoints = listOf(
                        sleepBlock.startMillis - 30 * 60_000L to "Pre-sleep",
                        sleepBlock.endMillis   - 15 * 60_000L to "Gentle",
                        sleepBlock.endMillis   - 10 * 60_000L to "Alarm",
                        sleepBlock.endMillis                   to "Ring",
                    )
                    alarmPoints.forEach { (alarmMs, label) ->
                        val alarmMin = msToMin(alarmMs, viewStartMs)
                        if (alarmMin !in 0..(TOTAL_HOURS * 60)) return@forEach
                        val alarmY = minToY(alarmMin)
                        Canvas(
                            Modifier
                                .yOffset(alarmY)
                                .fillMaxWidth()
                                .height(1.dp)
                        ) {
                            drawLine(
                                color = alarmAccent.copy(alpha = 0.5f),
                                start = Offset(0f, 0f),
                                end = Offset(size.width, 0f),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 3.dp.toPx()))
                            )
                        }
                        if (label != null) {
                            Row(
                                Modifier
                                    .yOffset(alarmY - 8.dp)
                                    .fillMaxWidth()
                                    .padding(end = 6.dp),
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
                }

                // Current-time indicator — red dot + line (when now falls in this window)
                if (isNowVisible) {
                    val clampedNow = nowMin.coerceIn(0, TOTAL_HOURS * 60)
                    val nowY = minToY(clampedNow)
                    val redC = Color(0xFFE53935)
                    Canvas(
                        Modifier
                            .yOffset(nowY - 4.dp)
                            .fillMaxWidth()
                            .height(8.dp)
                    ) {
                        val cy = size.height / 2f
                        drawCircle(redC, 4.dp.toPx(), Offset(0f, cy))
                        drawLine(redC, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1.5.dp.toPx())
                    }
                }
            }
        }
    }
}

/** Positions a child at an absolute y offset within its parent Box via layout. */
private fun Modifier.yOffset(y: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.placeRelative(0, y.roundToPx())
    }
}

/** Returns minutes elapsed since the 4 AM view-start anchor. Negative = before the window. */
private fun minutesFromViewStart(viewStartMs: Long): Int =
    ((System.currentTimeMillis() - viewStartMs) / 60_000L).toInt()

/** Converts an absolute timestamp to minutes relative to the 4 AM view-start anchor. */
private fun msToMin(ms: Long, viewStartMs: Long): Int =
    ((ms - viewStartMs) / 60_000L).toInt()

private fun fmtMs(ms: Long): String =
    Calendar.getInstance().apply { timeInMillis = ms }
        .let { "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }
