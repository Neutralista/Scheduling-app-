package com.waypoint.app.home

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waypoint.app.persistence.TaskEntry
import com.waypoint.app.persistence.TaskStore
import com.waypoint.app.planner.ShiftCalendarSync
import com.waypoint.app.signal.ShiftTime
import com.waypoint.app.signal.WorkScheduleSignals
import com.waypoint.app.ui.components.TimePickerChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

// ── Tab root ──────────────────────────────────────────────────────────────────

@Composable
fun TasksTab(workSchedule: WorkScheduleSignals, onRefresh: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { TaskStore(context) }

    var tasks by remember { mutableStateOf(store.loadToday()) }
    var input by remember { mutableStateOf("") }

    val dayFmt = remember { DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()) }
    var headerClock by remember { mutableStateOf(clockNow()) }
    var headerDay by remember { mutableStateOf(LocalDate.now().format(dayFmt)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            headerClock = clockNow()
            headerDay = LocalDate.now().format(dayFmt)
        }
    }

    fun submit() {
        val title = input.trim()
        if (title.isNotEmpty()) {
            tasks = store.add(title)
            input = ""
            onRefresh()
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ── Header bar: clock + day ───────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = headerClock,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Light,
                    letterSpacing = (-1).sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = headerDay,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Add a task…", style = MaterialTheme.typography.bodyMedium) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() })
            )
            TextButton(onClick = { submit() }, enabled = input.isNotBlank()) {
                Text("Add")
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        ShiftTaskRow(ws = workSchedule, context = context, onRefresh = onRefresh)

        if (tasks.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "No tasks for today",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(tasks, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        onToggle = { tasks = store.setDone(task.id, !task.done); onRefresh() },
                        onDelete = { tasks = store.delete(task.id); onRefresh() }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }

            val doneCount = tasks.count { it.done }
            if (doneCount > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "$doneCount done",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    TextButton(onClick = { tasks = store.clearCompleted(); onRefresh() }) {
                        Text("Clear completed", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

// ── Round checkbox ────────────────────────────────────────────────────────────

@Composable
private fun RoundCheckbox(
    checked: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline

    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(if (checked) primary else Color.Transparent)
            .border(1.5.dp, if (checked) primary else outline.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

// ── Shift task card ───────────────────────────────────────────────────────────

@Composable
private fun ShiftTaskRow(ws: WorkScheduleSignals, context: Context, onRefresh: () -> Unit) {
    val schedule = ws.getTodaySchedule()
    if (!schedule.isWork) return

    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(ws.getTodaySession()) }

    val isClockedIn = session.actualStartMillis != null && session.actualEndMillis == null
    val isClockedOut = session.actualStartMillis != null && session.actualEndMillis != null

    // Declared unconditionally to satisfy Compose slot table rules
    var manualStartTime by remember { mutableStateOf(clockNow()) }
    var manualEndTime by remember { mutableStateOf(clockNow()) }

    // Elapsed / remaining (no clock — shown in the tab header bar)
    var elapsedText by remember { mutableStateOf("") }
    var remainingText by remember { mutableStateOf("") }
    var isOvertime by remember { mutableStateOf(false) }

    val plannedEndMillis: Long? = remember(schedule) {
        val end = schedule.shiftEnd ?: return@remember null
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, end.hour)
            set(Calendar.MINUTE, end.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (schedule.crossesMidnight) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
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

    // Seed elapsed/remaining immediately on first composition
    LaunchedEffect(session) {
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

    // Snap the "Ended at" chip to current time when a shift becomes active
    LaunchedEffect(isClockedIn) {
        if (isClockedIn) manualEndTime = clockNow()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        // ── Shift time + status badge ─────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (schedule.shiftStart != null) {
                val endStr = schedule.shiftEnd?.displayString ?: "?"
                val suffix = if (schedule.crossesMidnight) " +1" else ""
                Text(
                    text = "${schedule.shiftStart.displayString} – $endStr$suffix",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = if (isClockedOut) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = when {
                        isClockedOut -> "Done"
                        isClockedIn  -> "On shift"
                        else         -> "Work day"
                    },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isClockedOut) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // ── Action section ────────────────────────────────────────────────
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 14.dp)
        ) {
            when {
                !isClockedIn && !isClockedOut -> {
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
                        TimePickerChip(
                            value = manualStartTime,
                            onValueChange = { manualStartTime = it },
                            modifier = Modifier.width(52.dp)
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    val millis = parseShiftStartMillis(manualStartTime)
                                        ?: System.currentTimeMillis()
                                    ws.startShift(millis)
                                    session = ws.getTodaySession()
                                    onRefresh()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("▶  Start shift", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                isClockedIn -> {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column {
                                Text(
                                    text = "Started ${formatShiftTime(session.actualStartMillis!!)}",
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
                                    TimePickerChip(
                                        value = manualEndTime,
                                        onValueChange = { manualEndTime = it },
                                        modifier = Modifier.width(52.dp)
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val startMs = session.actualStartMillis
                                                ?: System.currentTimeMillis()
                                            val endMs = parseShiftEndMillis(manualEndTime, startMs)
                                                ?: System.currentTimeMillis()
                                            ws.endShift(endMs)
                                            ShiftCalendarSync.write(context, ws, startMs, endMs)
                                            session = ws.getTodaySession()
                                            elapsedText = ""
                                            onRefresh()
                                        }
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Text("■  End shift", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                else -> {
                    // Completed
                    val startMs = session.actualStartMillis ?: 0L
                    val endMs = session.actualEndMillis ?: 0L
                    val startStr = formatShiftTime(startMs)
                    val endStr = formatShiftTime(endMs)
                    val dur = elapsedString(endMs - startMs)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Ended $endStr",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$startStr – $endStr · $dur",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        TextButton(onClick = {
                            scope.launch {
                                ws.resetTodaySession()
                                session = ws.getTodaySession()
                                elapsedText = ""
                                onRefresh()
                            }
                        }) {
                            Text(
                                "Reset",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Regular task row ──────────────────────────────────────────────────────────

@Composable
private fun TaskRow(
    task: TaskEntry,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RoundCheckbox(checked = task.done, onClick = onToggle)
        Text(
            text = task.title,
            style = MaterialTheme.typography.bodyMedium.copy(
                textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None
            ),
            color = if (task.done)
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Delete task",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun clockNow(): String {
    val c = Calendar.getInstance()
    return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

private fun formatShiftTime(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

private fun elapsedString(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun parseShiftStartMillis(timeStr: String): Long? {
    val t = ShiftTime.parse(timeStr) ?: return null
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, t.hour)
        set(Calendar.MINUTE, t.minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (cal.timeInMillis > System.currentTimeMillis() + 60_000L) {
        cal.add(Calendar.DAY_OF_YEAR, -1)
    }
    return cal.timeInMillis
}

private fun parseShiftEndMillis(timeStr: String, startMillis: Long): Long? {
    val t = ShiftTime.parse(timeStr) ?: return null
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, t.hour)
        set(Calendar.MINUTE, t.minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (cal.timeInMillis < startMillis) {
        cal.add(Calendar.DAY_OF_YEAR, 1)
    }
    return cal.timeInMillis
}
