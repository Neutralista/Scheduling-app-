package com.waypoint.app.home

import android.content.Context
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waypoint.app.planner.DayTimelineView
import com.waypoint.app.planner.EventPlannerRegistry
import com.waypoint.app.signal.CalendarSignals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CalendarTab(
    calendarSignals: CalendarSignals,
    eventPlanner: EventPlannerRegistry
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }

    var selectedDate by remember { mutableStateOf(today) }
    var displayMonth by remember { mutableStateOf(YearMonth.now()) }
    var showAddEvent by remember { mutableStateOf(false) }
    var calRefreshKey by remember { mutableIntStateOf(0) }
    var eventDays by remember { mutableStateOf(emptySet<LocalDate>()) }

    val hasReadPermission = remember { calendarSignals.hasPermission() }

    LaunchedEffect(displayMonth, calRefreshKey, hasReadPermission) {
        if (!hasReadPermission) return@LaunchedEffect
        eventDays = loadEventDaysForMonth(context, displayMonth)
    }

    val monthFmt = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()) }
    val dayHeaderFmt = remember { DateTimeFormatter.ofPattern("EEE, MMMM d", Locale.getDefault()) }

    Column(Modifier.fillMaxSize()) {

        // ── Month navigation ──────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { displayMonth = displayMonth.minusMonths(1) }) {
                Icon(
                    Icons.Filled.KeyboardArrowLeft,
                    contentDescription = "Previous month",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = displayMonth.format(monthFmt),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
            IconButton(onClick = { displayMonth = displayMonth.plusMonths(1) }) {
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = "Next month",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Weekday labels ────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                )
            }
        }

        // ── Month grid ────────────────────────────────────────────────────────
        MonthGrid(
            month = displayMonth,
            selectedDate = selectedDate,
            eventDays = eventDays,
            today = today,
            onDayClick = { date ->
                selectedDate = date
                val clicked = YearMonth.from(date)
                if (clicked != displayMonth) displayMonth = clicked
            }
        )

        HorizontalDivider(
            modifier = Modifier.padding(top = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        // ── Selected day header ───────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val dayLabel = when (selectedDate) {
                today              -> "Today · ${selectedDate.format(dayHeaderFmt)}"
                today.minusDays(1) -> "Yesterday · ${selectedDate.format(dayHeaderFmt)}"
                today.plusDays(1)  -> "Tomorrow · ${selectedDate.format(dayHeaderFmt)}"
                else               -> selectedDate.format(dayHeaderFmt)
            }
            Text(
                text = dayLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            TextButton(onClick = { showAddEvent = true }) { Text("+ Event") }
        }

        // ── Day timeline ──────────────────────────────────────────────────────
        DayTimelineView(
            registry = eventPlanner,
            calendarSignals = calendarSignals,
            date = selectedDate,
            refreshKey = calRefreshKey,
            modifier = Modifier.weight(1f)
        )
    }

    if (showAddEvent) {
        AddCalendarEventSheet(
            date = selectedDate,
            onDismiss = { showAddEvent = false },
            onSave = { title, startMs, endMs, notes, allDay ->
                scope.launch {
                    calendarSignals.createEvent(title, startMs, endMs, notes, allDay)
                    calRefreshKey++
                    showAddEvent = false
                }
            }
        )
    }
}

// ── Month grid ─────────────────────────────────────────────────────────────────

@Composable
private fun MonthGrid(
    month: YearMonth,
    selectedDate: LocalDate,
    eventDays: Set<LocalDate>,
    today: LocalDate,
    onDayClick: (LocalDate) -> Unit
) {
    val firstDay = month.atDay(1)
    val startOffset = firstDay.dayOfWeek.value - 1  // 0 = Mon, 6 = Sun
    val daysInMonth = month.lengthOfMonth()
    val totalWeeks = (startOffset + daysInMonth + 6) / 7

    Column(Modifier.fillMaxWidth()) {
        for (week in 0 until totalWeeks) {
            Row(Modifier.fillMaxWidth()) {
                for (dow in 0 until 7) {
                    val dayNum = week * 7 + dow - startOffset + 1
                    Box(Modifier.weight(1f)) {
                        if (dayNum in 1..daysInMonth) {
                            val date = month.atDay(dayNum)
                            DayCell(
                                day = dayNum,
                                isToday = date == today,
                                isSelected = date == selectedDate,
                                hasEvents = date in eventDays,
                                onClick = { onDayClick(date) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    isToday: Boolean,
    isSelected: Boolean,
    hasEvents: Boolean,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer

    val circleBg = when {
        isSelected -> primary
        isToday    -> primaryContainer
        else       -> Color.Transparent
    }
    val textColor = when {
        isSelected -> onPrimary
        isToday    -> onPrimaryContainer
        else       -> MaterialTheme.colorScheme.onSurface
    }
    val dotColor = if (isSelected) onPrimary.copy(alpha = 0.65f) else primary

    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(circleBg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = day.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = textColor
            )
        }
        if (hasEvents) {
            Box(
                Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        } else {
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ── Range query — which days in a month have at least one event ────────────────

private suspend fun loadEventDaysForMonth(context: Context, month: YearMonth): Set<LocalDate> =
    withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val startMs = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = month.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMs.toString())
            .appendPath(endMs.toString())
            .build()

        val days = mutableSetOf<LocalDate>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.BEGIN),
                null, null, null
            )?.use { cursor ->
                val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                while (cursor.moveToNext()) {
                    days.add(
                        Instant.ofEpochMilli(cursor.getLong(beginIdx))
                            .atZone(zone).toLocalDate()
                    )
                }
            }
        }
        days
    }
