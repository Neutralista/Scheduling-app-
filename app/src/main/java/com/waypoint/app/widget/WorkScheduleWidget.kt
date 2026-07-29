package com.waypoint.app.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waypoint.app.notification.ShiftAlarmScheduler
import com.waypoint.app.ui.components.TimePickerChip
import com.waypoint.app.signal.AfterWorkEvent
import com.waypoint.app.signal.DaySchedule
import com.waypoint.app.signal.ShiftSession
import com.waypoint.app.signal.ShiftTime
import com.waypoint.app.signal.WorkScheduleConfig
import com.waypoint.app.signal.WorkScheduleSignals
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale
import java.util.UUID

private val TABS = listOf("This week", "Next week", "Month", "Defaults")

@Composable
fun WorkScheduleCard(ws: WorkScheduleSignals, onShiftEnd: (() -> Unit)? = null) {
    val context = LocalContext.current
    val config by ws.configFlow.collectAsState()
    var session by remember { mutableStateOf(ws.getTodaySession()) }
        var selectedTab by remember { mutableIntStateOf(0) }
        var selectedMonthDate by remember { mutableStateOf<LocalDate?>(null) }
        val scope = rememberCoroutineScope()

        var clockText by remember { mutableStateOf(clockNow()) }
        var elapsedText by remember { mutableStateOf("") }
        var remainingText by remember { mutableStateOf("") }
        var isOvertime by remember { mutableStateOf(false) }

        val today = LocalDate.now()
        val todaySchedule = ws.getTodaySchedule()

        // Planned shift end as epoch millis (null when no shift end configured)
        val plannedEndMillis: Long? = remember(todaySchedule) {
            val end = todaySchedule.shiftEnd ?: return@remember null
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, end.hour)
                set(Calendar.MINUTE, end.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (todaySchedule.crossesMidnight) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
        }

        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                clockText = clockNow()
                val startMs = session.actualStartMillis
                if (startMs != null && session.actualEndMillis == null) {
                    elapsedText = elapsedString(System.currentTimeMillis() - startMs)
                    if (plannedEndMillis != null) {
                        val remaining = plannedEndMillis - System.currentTimeMillis()
                        isOvertime = remaining < 0
                        remainingText = if (remaining > 0)
                            "${elapsedString(remaining)} left"
                        else
                            "${elapsedString(-remaining)} overtime"
                    }
                }
            }
        }
        val onShift = ws.isOnShiftNow()

        val onWeekday: (Int, DaySchedule) -> Unit = { isoDay, sched ->
            scope.launch { ws.setWeekday(isoDay, sched) }
        }
        val onDateOverride: (String, DaySchedule) -> Unit = { key, sched ->
            scope.launch { ws.setDateOverride(key, sched) }
        }
        val onRemoveOverride: (String) -> Unit = { key ->
            scope.launch { ws.removeDateOverride(key) }
        }
        val onStartShift: (Long) -> Unit = { startMillis ->
            scope.launch {
                ws.startShift(startMillis)
                session = ws.getTodaySession()
                plannedEndMillis?.let { ShiftAlarmScheduler.schedule(context, it) }
            }
        }
        val onEndShift: (Long) -> Unit = { endMillis ->
            scope.launch {
                ws.endShift(endMillis)
                session = ws.getTodaySession()
                onShiftEnd?.invoke()
                ShiftAlarmScheduler.cancel(context)
            }
        }
        val onResetSession: () -> Unit = {
            scope.launch { ws.resetTodaySession(); session = ws.getTodaySession(); elapsedText = "" }
        }
        val onAddEvent: (AfterWorkEvent) -> Unit = { event ->
            scope.launch { ws.addAfterWorkEvent(event) }
        }
        val onRemoveEvent: (String) -> Unit = { eventId ->
            scope.launch { ws.removeAfterWorkEvent(eventId) }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            // ── Clock + status ───────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = clockText,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = (-1).sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(horizontalAlignment = Alignment.End) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (todaySchedule.isWork) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                    ) {
                        Text(
                            text = when {
                                !todaySchedule.isWork -> "Day off"
                                onShift -> "On shift"
                                else -> "Work day"
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (todaySchedule.isWork) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (todaySchedule.isWork && todaySchedule.shiftStart != null) {
                        Spacer(Modifier.height(3.dp))
                        val endStr = todaySchedule.shiftEnd?.displayString ?: "?"
                        val suffix = if (todaySchedule.crossesMidnight) " +1" else ""
                        Text(
                            text = "${todaySchedule.shiftStart.displayString} – $endStr$suffix",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        )
                    }
                }
            }

            // ── Shift button + session state ─────────────────────────────
            if (todaySchedule.isWork) {
                ShiftButtonSection(
                    session = session,
                    elapsedText = elapsedText,
                    remainingText = if (session.actualStartMillis != null && session.actualEndMillis == null && plannedEndMillis != null) remainingText else "",
                    isOvertime = isOvertime,
                    onStartShift = onStartShift,
                    onEndShift = onEndShift,
                    onResetSession = onResetSession
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // ── After-work events ────────────────────────────────────────
            if (todaySchedule.isWork) {
                AfterWorkSection(
                    events = config.afterWorkEvents,
                    ws = ws,
                    session = session,
                    onAddEvent = onAddEvent,
                    onRemoveEvent = onRemoveEvent
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            // ── Tab bar ──────────────────────────────────────────────────
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                edgePadding = 12.dp
            ) {
                TABS.forEachIndexed { i, label ->
                    Tab(
                        selected = selectedTab == i,
                        onClick = { selectedTab = i; selectedMonthDate = null },
                        text = { Text(label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }

            // ── Tab content ───────────────────────────────────────────────
            when (selectedTab) {
                0, 1 -> {
                    val weekOffset = selectedTab
                    val monday = today.with(DayOfWeek.MONDAY).plusWeeks(weekOffset.toLong())
                    WeekContent(
                        weekStart = monday,
                        today = today,
                        config = config,
                        ws = ws,
                        onDateOverride = onDateOverride,
                        onRemoveOverride = onRemoveOverride
                    )
                }
                2 -> MonthContent(
                    today = today,
                    config = config,
                    ws = ws,
                    selectedDate = selectedMonthDate,
                    onSelectDate = { selectedMonthDate = it },
                    onDateOverride = onDateOverride,
                    onRemoveOverride = onRemoveOverride
                )
                3 -> DefaultsContent(config = config, onWeekdayChange = onWeekday)
            }
        }
}

// ── Shift button section ──────────────────────────────────────────────────────

@Composable
private fun ShiftButtonSection(
    session: ShiftSession,
    elapsedText: String,
    remainingText: String,
    isOvertime: Boolean,
    onStartShift: (Long) -> Unit,
    onEndShift: (Long) -> Unit,
    onResetSession: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        when {
            session.actualStartMillis == null -> {
                var manualStartTime by remember { mutableStateOf(clockNow()) }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Started at",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    TimeField(value = manualStartTime, onValueChange = { manualStartTime = it })
                    Button(
                        onClick = {
                            val millis = parseTimeToMillis(manualStartTime) ?: System.currentTimeMillis()
                            onStartShift(millis)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("▶  Start shift", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            session.actualEndMillis == null -> {
                // Active session
                var manualEndTime by remember { mutableStateOf(clockNow()) }
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column {
                            Text(
                                text = "Started ${formatEpochAsTime(session.actualStartMillis!!)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (elapsedText.isNotEmpty()) {
                                Text(
                                    text = elapsedText,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (remainingText.isNotEmpty()) {
                                Text(
                                    text = remainingText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isOvertime) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "Ended at",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                TimeField(value = manualEndTime, onValueChange = { manualEndTime = it })
                            }
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = {
                                    val millis = parseTimeToMillis(manualEndTime) ?: System.currentTimeMillis()
                                    onEndShift(millis)
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                                )
                            ) {
                                Text("■  End shift", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            else -> {
                // Session ended
                val start = formatEpochAsTime(session.actualStartMillis)
                val end = formatEpochAsTime(session.actualEndMillis)
                val dur = durationString(session.actualStartMillis, session.actualEndMillis)
                Column {
                    Text(
                        text = "Ended $end",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$start – $end · $dur",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                TextButton(onClick = onResetSession) {
                    Text("Reset", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ── After-work events section ─────────────────────────────────────────────────

@Composable
private fun AfterWorkSection(
    events: List<AfterWorkEvent>,
    ws: WorkScheduleSignals,
    session: ShiftSession,
    onAddEvent: (AfterWorkEvent) -> Unit,
    onRemoveEvent: (String) -> Unit
) {
    var addTitle by remember { mutableStateOf("") }
    var addOffset by remember { mutableStateOf("0") }
    val isConfirmed = session.actualEndMillis != null

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "After work",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (events.isEmpty()) {
            Text(
                text = "No events — add one below",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
        } else {
            events.forEach { event ->
                val millis = ws.getScheduledTime(event)
                AfterWorkEventRow(
                    event = event,
                    scheduledMillis = millis,
                    isConfirmed = isConfirmed,
                    onRemove = { onRemoveEvent(event.id) }
                )
            }
        }

        // Add form
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = addTitle,
                onValueChange = { addTitle = it },
                placeholder = { Text("Event name", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = addOffset,
                onValueChange = { v -> addOffset = v.filter { it.isDigit() }.take(4) },
                placeholder = { Text("min", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.width(72.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Button(
                onClick = {
                    val title = addTitle.trim()
                    val offset = addOffset.toIntOrNull() ?: 0
                    if (title.isNotEmpty()) {
                        onAddEvent(AfterWorkEvent(UUID.randomUUID().toString(), title, offset))
                        addTitle = ""
                        addOffset = "0"
                    }
                },
                enabled = addTitle.isNotBlank()
            ) {
                Text("Add", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun AfterWorkEventRow(
    event: AfterWorkEvent,
    scheduledMillis: Long?,
    isConfirmed: Boolean,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isConfirmed) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Time
        val timeStr = if (scheduledMillis != null) {
            val prefix = if (isConfirmed) "" else "~"
            "$prefix${formatEpochAsTime(scheduledMillis)}"
        } else {
            "—"
        }
        Text(
            text = timeStr,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFeatureSettings = "tnum",
                fontWeight = if (isConfirmed) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = if (isConfirmed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.width(48.dp)
        )

        // Title
        Text(
            text = event.title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )

        // Offset label
        if (event.offsetMinutes > 0) {
            Text(
                text = "+${event.offsetMinutes}m",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
        }

        // Delete
        Text(
            text = "×",
            modifier = Modifier.clickable { onRemove() },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

// ── Week view ────────────────────────────────────────────────────────────────

@Composable
private fun WeekContent(
    weekStart: LocalDate,
    today: LocalDate,
    config: WorkScheduleConfig,
    ws: WorkScheduleSignals,
    onDateOverride: (String, DaySchedule) -> Unit,
    onRemoveOverride: (String) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DayOfWeek.values().forEachIndexed { i, dow ->
            val date = weekStart.plusDays(i.toLong())
            val key = localDateKey(date)
            val hasOverride = config.dateOverrides.containsKey(key)
            val schedule = config.dateOverrides[key]
                ?: config.weekdayDefaults[dow.value]
                ?: DaySchedule(dow.value in 1..5)
            DayScheduleRow(
                dayLabel = dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                subLabel = date.dayOfMonth.toString(),
                schedule = schedule,
                isToday = date == today,
                hasOverride = hasOverride,
                onScheduleChange = { onDateOverride(key, it) },
                onClearOverride = if (hasOverride) ({ onRemoveOverride(key) }) else null
            )
        }
    }
}

// ── Defaults view ────────────────────────────────────────────────────────────

@Composable
private fun DefaultsContent(
    config: WorkScheduleConfig,
    onWeekdayChange: (Int, DaySchedule) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Applied each week unless a date is overridden",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
        DayOfWeek.values().forEach { dow ->
            val schedule = config.weekdayDefaults[dow.value] ?: DaySchedule(dow.value in 1..5)
            DayScheduleRow(
                dayLabel = dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                subLabel = dow.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                schedule = schedule,
                isToday = false,
                hasOverride = false,
                onScheduleChange = { onWeekdayChange(dow.value, it) }
            )
        }
    }
}

// ── Month view ───────────────────────────────────────────────────────────────

@Composable
private fun MonthContent(
    today: LocalDate,
    config: WorkScheduleConfig,
    ws: WorkScheduleSignals,
    selectedDate: LocalDate?,
    onSelectDate: (LocalDate?) -> Unit,
    onDateOverride: (String, DaySchedule) -> Unit,
    onRemoveOverride: (String) -> Unit
) {
    val firstDay = today.withDayOfMonth(1)
    val daysInMonth = today.lengthOfMonth()
    val startPadding = firstDay.dayOfWeek.value - 1  // 0 = Mon, 6 = Sun

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // Day-of-week column headers
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { h ->
                Text(
                    text = h,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        // Calendar grid
        val totalCells = startPadding + daysInMonth
        val rows = (totalCells + 6) / 7
        for (row in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val dayNum = row * 7 + col - startPadding + 1
                    if (dayNum < 1 || dayNum > daysInMonth) {
                        Box(Modifier.weight(1f))
                    } else {
                        val date = today.withDayOfMonth(dayNum)
                        val key = localDateKey(date)
                        val schedule = config.dateOverrides[key]
                            ?: config.weekdayDefaults[date.dayOfWeek.value]
                            ?: DaySchedule(date.dayOfWeek.value in 1..5)
                        val isSelected = date == selectedDate
                        val isToday = date == today

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(2.dp)
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        schedule.isWork -> MaterialTheme.colorScheme.primaryContainer
                                        else -> MaterialTheme.colorScheme.surface
                                    }
                                )
                                .then(
                                    if (isToday && !isSelected)
                                        Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                    else Modifier
                                )
                                .clickable { onSelectDate(if (isSelected) null else date) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = dayNum.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimary
                                    schedule.isWork -> MaterialTheme.colorScheme.onPrimaryContainer
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }
        }

        // Selected-date editor
        if (selectedDate != null) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            val selKey = localDateKey(selectedDate)
            val hasOverride = config.dateOverrides.containsKey(selKey)
            val selSchedule = config.dateOverrides[selKey]
                ?: config.weekdayDefaults[selectedDate.dayOfWeek.value]
                ?: DaySchedule(selectedDate.dayOfWeek.value in 1..5)
            DayScheduleRow(
                dayLabel = selectedDate.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                subLabel = "${selectedDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${selectedDate.dayOfMonth}",
                schedule = selSchedule,
                isToday = selectedDate == today,
                hasOverride = hasOverride,
                onScheduleChange = { onDateOverride(selKey, it) },
                onClearOverride = if (hasOverride) ({ onRemoveOverride(selKey) }) else null
            )
        }
    }
}

// ── Day row ──────────────────────────────────────────────────────────────────

@Composable
private fun DayScheduleRow(
    dayLabel: String,
    subLabel: String,
    schedule: DaySchedule,
    isToday: Boolean,
    hasOverride: Boolean,
    onScheduleChange: (DaySchedule) -> Unit,
    onClearOverride: (() -> Unit)? = null
) {
    var startText by remember(schedule.shiftStart) {
        mutableStateOf(schedule.shiftStart?.displayString ?: "09:00")
    }
    var endText by remember(schedule.shiftEnd) {
        mutableStateOf(schedule.shiftEnd?.displayString ?: "17:00")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isToday) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Day label
        Column(Modifier.width(42.dp)) {
            Text(
                text = dayLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isToday) FontWeight.ExtraBold else FontWeight.Normal,
                color = if (isToday) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = subLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }

        // Work / Off chip
        FilterChip(
            selected = schedule.isWork,
            onClick = { onScheduleChange(schedule.copy(isWork = !schedule.isWork)) },
            label = { Text(if (schedule.isWork) "Work" else "Off", style = MaterialTheme.typography.labelSmall) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )

        // Shift time inputs
        AnimatedVisibility(visible = schedule.isWork) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TimeField(value = startText, onValueChange = { v ->
                    startText = v
                    ShiftTime.parse(v)?.let { t -> onScheduleChange(schedule.copy(shiftStart = t)) }
                })
                Text(
                    "–",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                TimeField(value = endText, onValueChange = { v ->
                    endText = v
                    ShiftTime.parse(v)?.let { t -> onScheduleChange(schedule.copy(shiftEnd = t)) }
                })
                if (schedule.crossesMidnight) {
                    Text(
                        "+1",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Reset-to-default button for overridden dates
        if (hasOverride && onClearOverride != null) {
            Text(
                text = "↺",
                modifier = Modifier.clickable { onClearOverride() },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            )
        }
    }
}

// ── Time field ───────────────────────────────────────────────────────────────

@Composable
private fun TimeField(value: String, onValueChange: (String) -> Unit) {
    TimePickerChip(value = value, onValueChange = onValueChange, modifier = Modifier.width(50.dp))
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun clockNow(): String {
    val c = Calendar.getInstance()
    return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

private fun formatEpochAsTime(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

private fun elapsedString(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun durationString(startMs: Long, endMs: Long): String = elapsedString(endMs - startMs)

private fun parseTimeToMillis(timeStr: String): Long? {
    val t = com.waypoint.app.signal.ShiftTime.parse(timeStr) ?: return null
    return Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, t.hour)
        set(Calendar.MINUTE, t.minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun localDateKey(date: LocalDate): String =
    "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)
