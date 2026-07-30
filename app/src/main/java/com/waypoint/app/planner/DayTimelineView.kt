package com.waypoint.app.planner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.waypoint.app.signal.WorkScheduleSignals
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
private val HOUR_HEIGHT = 58.dp
private val LABEL_WIDTH = 44.dp

@Composable
fun DayTimelineView(
    ws: WorkScheduleSignals,
    registry: EventPlannerRegistry,
    calendarSignals: CalendarSignals? = null,
    date: LocalDate = LocalDate.now(),
    refreshKey: Int = 0,
    modifier: Modifier = Modifier
) {
    // Anchor: 4 AM on the viewed date in ms. All "minute" values are relative to this.
    val viewStartMs = remember(date) {
        date.atTime(VIEW_START_HOUR, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
    }

    val dateCal = remember(date) {
        Calendar.getInstance().apply {
            set(Calendar.YEAR, date.year)
            set(Calendar.MONTH, date.monthValue - 1)
            set(Calendar.DAY_OF_MONTH, date.dayOfMonth)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
        }
    }
    val viewEndMs = viewStartMs + TOTAL_HOURS * 3600_000L
    val isNowVisible = System.currentTimeMillis() in viewStartMs until viewEndMs

    var plan by remember(date) { mutableStateOf(registry.planForDate(date, ws)) }
    // Next-day plan provides the post-midnight half of cross-midnight events inside the 4AM-4AM window
    var nextDayScheduled by remember(date) {
        mutableStateOf(registry.planForDate(date.plusDays(1), ws).scheduled)
    }
    val dateSchedule = remember(date) { ws.getSchedule(dateCal) }
    var nowMin by remember(viewStartMs) { mutableIntStateOf(minutesFromViewStart(viewStartMs)) }
    var calEvents by remember(date) { mutableStateOf<List<CalendarEvent>>(emptyList()) }
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

    // Calendar fetch: runs immediately, then every 15 minutes; also re-runs on refreshKey change
    LaunchedEffect(date, refreshKey) {
        suspend fun fetchCal() {
            if (calendarSignals?.hasPermission() == true) {
                val today = calendarSignals.eventsForDate(date)
                val nextDay = calendarSignals.eventsForDate(date.plusDays(1))
                calEvents = (today + nextDay)
            }
        }
        fetchCal()
        while (true) {
            delay(60_000L)
            fetchCal()
        }
    }

    // 1-minute ticker: update now-line and re-evaluate plan (when now falls in this window)
    LaunchedEffect(viewStartMs) {
        if (isNowVisible) {
            while (true) {
                delay(60_000L)
                nowMin = minutesFromViewStart(viewStartMs)
                plan = registry.planForDate(date, ws)
                nextDayScheduled = registry.planForDate(date.plusDays(1), ws).scheduled
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

    // Convert absolute LocalTime to relative minutes from the 4 AM view start
    fun absToRel(absMin: Int): Int {
        val rel = absMin - VIEW_START_HOUR * 60
        return if (rel < 0) rel + 1440 else rel
    }
    val shiftStartMin = dateSchedule.shiftStart?.let { absToRel(it.hour * 60 + it.minute) }
    val shiftEndMin   = dateSchedule.shiftEnd?.let { t ->
        val absMin = t.hour * 60 + t.minute + if (dateSchedule.crossesMidnight) 1440 else 0
        absToRel(absMin)
    }

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
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
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

                // Shift block
                if (dateSchedule.isWork && shiftStartMin != null && shiftEndMin != null) {
                    val startY = minToY(shiftStartMin)
                    val shiftH = (minToY(shiftEndMin) - startY).coerceAtLeast(4.dp)
                    Box(
                        Modifier
                            .yOffset(startY)
                            .fillMaxWidth()
                            .height(shiftH)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(primary.copy(alpha = 0.09f))
                            .border(1.dp, primary.copy(alpha = 0.20f), RoundedCornerShape(6.dp))
                    ) {
                        Text(
                            text = "Shift · ${dateSchedule.shiftStart!!.displayString}–${dateSchedule.shiftEnd?.displayString ?: "?"}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = primary.copy(alpha = 0.60f)
                        )
                    }
                }

                // Calendar event blocks — rendered first so planner events appear on top
                calEvents.filter { !it.allDay }.forEach { evt ->
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
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = calColor.copy(alpha = 0.85f),
                                maxLines = 1
                            )
                            if (eventH >= 36.dp) {
                                Text(
                                    "${fmtMs(evt.startMillis)} – ${fmtMs(evt.endMillis)}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color = calColor.copy(alpha = 0.55f)
                                )
                            }
                        }
                    }
                }

                // Planner event blocks
                // Merge today's events with post-midnight next-day events that fall inside
                // the 4AM-4AM window, then stitch adjacent blocks into one continuous bar.
                val viewEndMs = viewStartMs + TOTAL_HOURS * 3600_000L
                val mergedScheduled = run {
                    val combined = (plan.scheduled +
                        nextDayScheduled.filter { it.startMillis < viewEndMs })
                        .sortedBy { it.startMillis }
                    // Stitch adjacent blocks of the same logical event:
                    // - same event ID (fixed cross-midnight events split at midnight), or
                    // - both SLEEP category
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
                val eventColors = listOf(secCont to onSecCont, terCont to onTerCont)
                val sleepAccent = Color(0xFF6B8ABD)
                var habitIdx = 0
                mergedScheduled.forEach { se ->
                    val isSleep = se.event.category == EventCategory.SLEEP
                    val seStartMin = msToMin(se.startMillis, viewStartMs)
                    val seEndMin   = msToMin(se.endMillis,   viewStartMs)
                    val startY  = minToY(seStartMin)
                    val eventH  = (minToY(seEndMin) - startY - 2.dp).coerceAtLeast(24.dp)
                    val (bg, fg) = if (isSleep) {
                        sleepAccent.copy(alpha = 0.13f) to sleepAccent
                    } else {
                        eventColors[habitIdx++ % eventColors.size]
                    }

                    Box(
                        Modifier
                            .yOffset(startY + 1.dp)
                            .fillMaxWidth()
                            .height(eventH)
                            .padding(horizontal = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(bg)
                            .then(
                                if (isSleep) Modifier.border(1.dp, sleepAccent.copy(alpha = 0.40f), RoundedCornerShape(6.dp))
                                else Modifier
                            )
                    ) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                se.event.title,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = fg,
                                maxLines = 1
                            )
                            if (eventH >= 36.dp) {
                                Text(
                                    "${fmtMs(se.startMillis)} – ${fmtMs(se.endMillis)}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color = fg.copy(alpha = 0.65f)
                                )
                            }
                        }
                    }
                }

                // Alarm markers — derived from the sleep block's bed/wake times
                val alarmAccent = Color(0xFFF59E0B)
                val sleepBlock = mergedScheduled.firstOrNull { it.event.category == EventCategory.SLEEP }
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
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
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
