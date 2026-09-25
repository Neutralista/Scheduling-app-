package com.waypoint.app.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.waypoint.app.planner.DaySummary
import com.waypoint.app.planner.OverviewItem
import com.waypoint.app.planner.OverviewKind
import com.waypoint.app.planner.PlanZoomLevel
import com.waypoint.app.planner.monthWeekStarts
import com.waypoint.app.planner.weekDays
import com.waypoint.app.ui.components.toOpaqueColor
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale

// The Plan tab's zoomed-out levels: Week (a tile per day), Month (a tile per week) and Year (a
// tile per month). Tapping a tile zooms in to it; pinching steps between levels.

/**
 * Pinch between zoom levels: spreading two fingers calls [onZoomIn], pinching them together
 * [onZoomOut], once per gesture. One-finger scrolling and taps pass through untouched.
 */
@Composable
fun Modifier.pinchZoomLevels(onZoomIn: () -> Unit, onZoomOut: () -> Unit): Modifier {
    // The gesture loop outlives recompositions; read the latest callbacks, not the first ones.
    val zoomIn by rememberUpdatedState(onZoomIn)
    val zoomOut by rememberUpdatedState(onZoomOut)
    return this.then(Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var accumulated = 1f
            var fired = false
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2 && !fired) {
                    accumulated *= event.calculateZoom()
                    pressed.forEach { it.consume() }
                    when {
                        accumulated > 1.25f -> { fired = true; zoomIn() }
                        accumulated < 0.8f -> { fired = true; zoomOut() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    })
}

/** Year · Month · Week · Day, highlighting [level]. */
@Composable
fun ZoomLevelSwitcher(level: PlanZoomLevel, onSelect: (PlanZoomLevel) -> Unit, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, outline.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
    ) {
        listOf(PlanZoomLevel.YEAR, PlanZoomLevel.MONTH, PlanZoomLevel.WEEK, PlanZoomLevel.DAY).forEach { l ->
            val selected = l == level
            Text(
                text = l.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(l) }
                    .padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun itemColor(item: OverviewItem): Color =
    item.colorArgb?.toOpaqueColor() ?: when (item.kind) {
        OverviewKind.BLOCK -> MaterialTheme.colorScheme.primary
        OverviewKind.TASK -> MaterialTheme.colorScheme.tertiary
        OverviewKind.EVENT -> MaterialTheme.colorScheme.secondary
    }

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private fun fmt(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFmt)

@Composable
private fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** A tile: a tinted rounded card, outlined when it's today's (or holds today). */
@Composable
private fun OverviewTile(
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .then(if (highlighted) Modifier.border(1.5.dp, primary, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) { content() }
}

// ── Week: a tile per day ──────────────────────────────────────────────────────

/**
 * Seven day tiles for [anchor]'s week. Each has the day, a strip of the day from 06:00 to
 * midnight with its blocks, tasks and events drawn where they fall, and its first few items.
 */
@Composable
fun WeekOverview(
    anchor: LocalDate,
    summaries: Map<LocalDate, DaySummary>?,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    if (summaries == null) { LoadingBox(modifier); return }
    val today = LocalDate.now()
    // Days whose dropdown is open: everything scheduled, blocks with their tasks.
    var expanded by remember(anchor) { mutableStateOf(emptySet<LocalDate>()) }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(weekDays(anchor), key = { it.toString() }) { date ->
            val items = summaries[date]?.items.orEmpty()
            val isOpen = date in expanded
            OverviewTile(highlighted = date == today, onClick = { onDayClick(date) }, modifier = Modifier.fillMaxWidth()) {
              Column {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            date.dayOfMonth.toString(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DayStrip(date, items)
                        if (items.isEmpty()) {
                            Text(
                                "Nothing planned",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            // The dropdown lists them all when it's open.
                            items.take(if (isOpen) 0 else 4).forEach { item ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.width(3.dp).height(14.dp).background(itemColor(item), RoundedCornerShape(2.dp)))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        (if (item.allDay) "All day" else fmt(item.startMs)) + "  " + item.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (items.size > 4 && !isOpen) {
                                Text(
                                    "+${items.size - 4} more",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    val taskCount = items.sumOf { if (it.kind == OverviewKind.BLOCK) it.subItems.size else 1 }
                    if (items.isNotEmpty()) {
                        IconButton(
                            onClick = { expanded = if (isOpen) expanded - date else expanded + date },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                if (isOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (isOpen) "Hide the day's schedule" else "Show all $taskCount scheduled",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (isOpen) DaySchedule(items)
              }
            }
        }
    }
}

/**
 * The dropdown's list: every item of the day in time order, each block followed by its tasks
 * (indented, under their phase when they have one), with lengths and, today, what's done.
 */
@Composable
private fun DaySchedule(items: List<OverviewItem>) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth().padding(start = 52.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Spacer(Modifier.height(4.dp))
        items.forEach { item ->
            ScheduleLine(item, indent = 0.dp, bold = item.kind == OverviewKind.BLOCK)
            var lastPhase: String? = null
            item.subItems.forEach { sub ->
                if (sub.phaseName != null && sub.phaseName != lastPhase) {
                    Text(
                        sub.phaseName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = itemColor(item),
                        modifier = Modifier.padding(start = 14.dp, top = 2.dp)
                    )
                }
                lastPhase = sub.phaseName
                ScheduleLine(sub, indent = 14.dp, bold = false, fallbackColor = itemColor(item))
            }
        }
        if (items.all { it.subItems.isEmpty() } && items.none { it.kind == OverviewKind.TASK }) {
            Text("No tasks scheduled", style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

@Composable
private fun ScheduleLine(item: OverviewItem, indent: androidx.compose.ui.unit.Dp, bold: Boolean, fallbackColor: Color? = null) {
    val color = item.colorArgb?.toOpaqueColor() ?: fallbackColor ?: itemColor(item)
    val minutes = ((item.endMs - item.startMs) / 60_000L).toInt()
    val length = when {
        item.allDay -> "all day"
        minutes >= 60 -> "${minutes / 60}h" + if (minutes % 60 > 0) " ${minutes % 60}m" else ""
        else -> "${minutes}m"
    }
    Row(Modifier.fillMaxWidth().padding(start = indent), verticalAlignment = Alignment.CenterVertically) {
        if (item.done) {
            Icon(Icons.Filled.Check, contentDescription = "Done", tint = color, modifier = Modifier.size(14.dp))
        } else {
            Box(Modifier.width(3.dp).height(14.dp).background(color, RoundedCornerShape(2.dp)))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            if (item.allDay) "All day" else fmt(item.startMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp)
        )
        Text(
            item.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (item.done) 0.5f else 1f),
            textDecoration = if (item.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!item.allDay) {
            Text(length, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The day from 06:00 to midnight as a bar, with each timed item drawn where it falls. */
@Composable
private fun DayStrip(date: LocalDate, items: List<OverviewItem>) {
    val zone = ZoneId.systemDefault()
    val from = date.atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
    val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val timed = items.filter { !it.allDay }
    val colors = timed.map { itemColor(it) }
    val now = System.currentTimeMillis()
    val nowColor = MaterialTheme.colorScheme.error
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val r = CornerRadius(3.dp.toPx())
        drawRoundRect(track, size = size, cornerRadius = r)
        val span = (to - from).toFloat()
        timed.forEachIndexed { i, item ->
            val s = ((item.startMs.coerceIn(from, to) - from) / span) * size.width
            val e = ((item.endMs.coerceIn(from, to) - from) / span) * size.width
            if (e - s > 0.5f) {
                // Tasks are drawn thinner so blocks and events read as the day's frame.
                val h = if (item.kind == OverviewKind.TASK) size.height * 0.55f else size.height
                drawRoundRect(colors[i], topLeft = Offset(s, (size.height - h) / 2), size = Size(e - s, h), cornerRadius = r)
            }
        }
        if (now in from until to) {
            val x = ((now - from) / span) * size.width
            drawRect(nowColor, topLeft = Offset(x - 1f, 0f), size = Size(2f, size.height))
        }
    }
}

// ── Month: a tile per week ────────────────────────────────────────────────────

/** A tile per week of [month]; each shows its seven days with coloured marks for their items. */
@Composable
fun MonthOverview(
    month: YearMonth,
    summaries: Map<LocalDate, DaySummary>?,
    onWeekClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    if (summaries == null) { LoadingBox(modifier); return }
    val today = LocalDate.now()
    // Not a list: the weeks share the screen's height, so every month fills it.
    Column(
        modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 10.dp)) {
            weekDays(month.atDay(1)).forEach { d ->
                Text(
                    d.dayOfWeek.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        monthWeekStarts(month).forEach { monday ->
            val days = weekDays(monday)
            OverviewTile(
                highlighted = today in days,
                onClick = { onWeekClick(monday) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) {
                    Text(
                        "W${monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(26.dp).padding(top = 2.dp)
                    )
                    days.forEach { date ->
                        MonthDayCell(
                            date = date,
                            inMonth = YearMonth.from(date) == month,
                            isToday = date == today,
                            items = summaries[date]?.items.orEmpty(),
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}

/**
 * A day in the month view: its number, then its items as small labelled chips, as many as the
 * cell's height fits, and a count of the rest.
 */
@Composable
private fun MonthDayCell(date: LocalDate, inMonth: Boolean, isToday: Boolean, items: List<OverviewItem>, modifier: Modifier) {
    val dim = if (inMonth) 1f else 0.4f
    BoxWithConstraints(modifier.padding(horizontal = 1.5.dp)) {
        val chipHeight = 16.dp
        val numberHeight = 22.dp
        val moreHeight = 14.dp
        val fits = ((maxHeight - numberHeight) / (chipHeight + 2.dp)).toInt().coerceAtLeast(0)
        // Room for all of them, or all but one line kept for "+n".
        val shown = if (items.size <= fits) items.size
            else ((maxHeight - numberHeight - moreHeight) / (chipHeight + 2.dp)).toInt().coerceAtLeast(0)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.height(numberHeight).then(
                    if (isToday) Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp) else Modifier
                ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = dim)
                )
            }
            items.take(shown).forEach { item ->
                val color = itemColor(item)
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .fillMaxWidth()
                        .height(chipHeight)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color.copy(alpha = 0.22f * dim + 0.08f))
                ) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(color.copy(alpha = dim)))
                    Text(
                        item.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp)
                    )
                }
            }
            if (shown < items.size) {
                Text(
                    "+${items.size - shown}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
                    modifier = Modifier.height(moreHeight)
                )
            }
        }
    }
}

// ── Year: a tile per month ────────────────────────────────────────────────────

/** Twelve month tiles for [year], each a small calendar shaded by how much each day holds. */
@Composable
fun YearOverview(
    year: Int,
    summaries: Map<LocalDate, DaySummary>?,
    onMonthClick: (YearMonth) -> Unit,
    modifier: Modifier = Modifier
) {
    if (summaries == null) { LoadingBox(modifier); return }
    val today = LocalDate.now()
    val months = (1..12).map { YearMonth.of(year, it) }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(months.chunked(3), key = { it.first().toString() }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { month ->
                    OverviewTile(
                        highlighted = YearMonth.from(today) == month,
                        onClick = { onMonthClick(month) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
                                    .replaceFirstChar { it.titlecase(Locale.getDefault()) },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            MiniMonth(month, today, summaries)
                        }
                    }
                }
            }
        }
    }
}

/** A tiny month calendar: a square per day, darker the more it holds; today outlined. */
@Composable
private fun MiniMonth(month: YearMonth, today: LocalDate, summaries: Map<LocalDate, DaySummary>) {
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    val todayRing = MaterialTheme.colorScheme.error
    val counts = remember(month, summaries) {
        (1..month.lengthOfMonth()).map { summaries[month.atDay(it)]?.items?.size ?: 0 }
    }
    val lead = month.atDay(1).dayOfWeek.value - 1
    val rows = (lead + month.lengthOfMonth() + 6) / 7
    Canvas(Modifier.fillMaxWidth().aspectRatio(7f / rows)) {
        val cell = size.width / 7f
        val gap = cell * 0.15f
        counts.forEachIndexed { i, count ->
            val pos = lead + i
            val topLeft = Offset((pos % 7) * cell + gap / 2, (pos / 7) * cell + gap / 2)
            val sq = Size(cell - gap, cell - gap)
            val color = if (count == 0) empty else primary.copy(alpha = (0.25f + 0.15f * count).coerceAtMost(1f))
            drawRoundRect(color, topLeft, sq, CornerRadius(cell * 0.2f))
            if (month.atDay(i + 1) == today) {
                drawRoundRect(todayRing, topLeft, sq, CornerRadius(cell * 0.2f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = gap))
            }
        }
    }
}

/** The header label for [level] around [anchor]: "Sep 21 – 27", "September 2026", "2026". */
fun zoomPeriodLabel(level: PlanZoomLevel, anchor: LocalDate): String = when (level) {
    PlanZoomLevel.DAY -> anchor.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
    PlanZoomLevel.WEEK -> {
        val days = weekDays(anchor)
        val first = days.first(); val last = days.last()
        val startFmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
        val endFmt = DateTimeFormatter.ofPattern(if (first.month == last.month) "d" else "MMM d", Locale.getDefault())
        "${first.format(startFmt)} – ${last.format(endFmt)}"
    }
    // LLLL: the month's name on its own ("wrzesień"), not the form used inside a date ("września").
    PlanZoomLevel.MONTH -> anchor.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    PlanZoomLevel.YEAR -> anchor.year.toString()
}
