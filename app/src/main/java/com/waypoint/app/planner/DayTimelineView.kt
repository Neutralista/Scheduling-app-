package com.waypoint.app.planner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waypoint.app.signal.WorkScheduleSignals
import kotlinx.coroutines.delay
import java.util.Calendar

private const val START_HOUR = 0
private const val END_HOUR = 24
private const val TOTAL_HOURS = END_HOUR - START_HOUR
private val HOUR_HEIGHT = 58.dp
private val LABEL_WIDTH = 44.dp

@Composable
fun DayTimelineView(
    ws: WorkScheduleSignals,
    registry: EventPlannerRegistry,
    modifier: Modifier = Modifier
) {
    var plan by remember { mutableStateOf(registry.planToday(ws)) }
    val todaySchedule = ws.getTodaySchedule()
    var nowMin by remember { mutableIntStateOf(minutesNow()) }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    LaunchedEffect(Unit) {
        // Scroll so current time is visible (one hour of context above)
        val scrollPx = with(density) {
            ((nowMin - START_HOUR * 60 - 60).coerceAtLeast(0) / 60f * HOUR_HEIGHT.toPx()).toInt()
        }
        scrollState.animateScrollTo(scrollPx)
        while (true) {
            delay(60_000L)
            nowMin = minutesNow()
            plan = registry.planToday(ws)
        }
    }

    val primary    = MaterialTheme.colorScheme.primary
    val outline    = MaterialTheme.colorScheme.outlineVariant
    val onSV       = MaterialTheme.colorScheme.onSurfaceVariant
    val secCont    = MaterialTheme.colorScheme.secondaryContainer
    val terCont    = MaterialTheme.colorScheme.tertiaryContainer
    val onSecCont  = MaterialTheme.colorScheme.onSecondaryContainer
    val onTerCont  = MaterialTheme.colorScheme.onTertiaryContainer

    val shiftStartMin = todaySchedule.shiftStart?.let { it.hour * 60 + it.minute }
    val shiftEndMin   = todaySchedule.shiftEnd?.let { t ->
        val m = t.hour * 60 + t.minute
        if (todaySchedule.crossesMidnight) m + 1440 else m
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
                        text = "%02d:00".format(h),
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
                if (todaySchedule.isWork && shiftStartMin != null && shiftEndMin != null) {
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
                            text = "Shift · ${todaySchedule.shiftStart!!.displayString}–${todaySchedule.shiftEnd?.displayString ?: "?"}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = primary.copy(alpha = 0.60f)
                        )
                    }
                }

                // Event blocks
                val eventColors = listOf(secCont to onSecCont, terCont to onTerCont)
                plan.scheduled.forEachIndexed { idx, se ->
                    val seStartMin = msToMin(se.startMillis)
                    val seEndMin   = msToMin(se.endMillis)
                    val startY  = minToY(seStartMin)
                    val eventH  = (minToY(seEndMin) - startY - 2.dp).coerceAtLeast(24.dp)
                    val (bg, fg) = eventColors[idx % eventColors.size]

                    Box(
                        Modifier
                            .yOffset(startY + 1.dp)
                            .fillMaxWidth()
                            .height(eventH)
                            .padding(horizontal = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(bg)
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

                // Current-time indicator — red dot + line
                val clampedNow = nowMin.coerceIn(START_HOUR * 60, END_HOUR * 60)
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

/** Positions a child at an absolute y offset within its parent Box via layout. */
private fun Modifier.yOffset(y: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.placeRelative(0, y.roundToPx())
    }
}

private fun minutesNow(): Int =
    Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

private fun msToMin(ms: Long): Int =
    Calendar.getInstance().apply { timeInMillis = ms }
        .let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

private fun fmtMs(ms: Long): String =
    Calendar.getInstance().apply { timeInMillis = ms }
        .let { "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }
