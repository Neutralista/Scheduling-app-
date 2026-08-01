package com.waypoint.app.home

import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.waypoint.app.persistence.TaskEntry
import com.waypoint.app.persistence.TaskStore
import com.waypoint.app.planner.ShiftCalendarSync
import com.waypoint.app.signal.WorkScheduleSignals
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

// ── Dialog state machine ──────────────────────────────────────────────────────

private enum class StartDialog { NONE, ON_TIME, HOW_TO_LOG, TIME_PICKER }

// ── Tab root ──────────────────────────────────────────────────────────────────

@Composable
fun TasksTab(workSchedule: WorkScheduleSignals, onRefresh: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { TaskStore(context) }

    var tasks by remember { mutableStateOf(store.loadToday()) }
    var input by remember { mutableStateOf("") }

    fun submit() {
        val title = input.trim()
        if (title.isNotEmpty()) {
            tasks = store.add(title)
            input = ""
            onRefresh()
        }
    }

    Column(Modifier.fillMaxSize()) {
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

// ── Shift task row ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShiftTaskRow(ws: WorkScheduleSignals, context: Context, onRefresh: () -> Unit) {
    val schedule = ws.getTodaySchedule()
    if (!schedule.isWork) return

    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(ws.getTodaySession()) }

    var nowMinutes by remember {
        mutableIntStateOf(Calendar.getInstance().let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        })
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMinutes = Calendar.getInstance().let {
                it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
            }
        }
    }

    val shiftStart = schedule.shiftStart
    val shiftEnd = schedule.shiftEnd

    val isClockedIn = session.actualStartMillis != null && session.actualEndMillis == null
    val isClockedOut = session.actualStartMillis != null && session.actualEndMillis != null

    // 30-min window: button enabled from 30 min before shift, or always if no defined start time
    val minutesUntilShift = shiftStart?.let { it.totalMinutes - nowMinutes } ?: -1
    val startEnabled = minutesUntilShift <= 30
    val isAfterScheduledStart = minutesUntilShift <= 0

    // Dialog flow state
    var dialog by remember { mutableStateOf(StartDialog.NONE) }
    var buttonPressMs by remember { mutableLongStateOf(0L) }

    // TimePicker state — defined unconditionally to satisfy Compose slot table rules
    val timePickerState = rememberTimePickerState(
        initialHour = shiftStart?.hour ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        initialMinute = shiftStart?.minute ?: Calendar.getInstance().get(Calendar.MINUTE),
        is24Hour = true
    )

    fun doStartShift(startMs: Long) {
        scope.launch {
            ws.startShift(startMs)
            session = ws.getTodaySession()
            onRefresh()
        }
    }

    // Title label
    val title = when {
        isClockedOut -> "Shift complete"
        isClockedIn -> {
            val endLabel = shiftEnd?.let { e ->
                val diff = e.totalMinutes - nowMinutes
                val adj = if (diff < 0) diff + 1440 else diff
                when {
                    adj <= 0 -> "ending soon"
                    adj < 60 -> "ends in ${adj}m"
                    else -> "ends in ${adj / 60}h ${adj % 60}m"
                }
            }
            if (endLabel != null) "Shift in progress · $endLabel" else "Shift in progress"
        }
        shiftStart != null -> when {
            minutesUntilShift <= 0 -> "Work · start shift"
            minutesUntilShift < 60 -> "Work in ${minutesUntilShift}m"
            else -> "Work in ${minutesUntilShift / 60}h ${minutesUntilShift % 60}m"
        }
        else -> "Work today"
    }

    val subtitle = buildString {
        if (shiftStart != null && shiftEnd != null) {
            append("${shiftStart.displayString}–${shiftEnd.displayString}")
        }
        if (isClockedOut) {
            val startMs = session.actualStartMillis
            val endMs = session.actualEndMillis
            if (startMs != null && endMs != null) {
                val durationMin = ((endMs - startMs) / 60_000).toInt()
                if (durationMin > 0) append(" · ${durationMin / 60}h ${durationMin % 60}m")
            }
        }
    }

    // ── Row ───────────────────────────────────────────────────────────────────
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RoundCheckbox(checked = isClockedOut, onClick = null)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        textDecoration = if (isClockedOut) TextDecoration.LineThrough else TextDecoration.None
                    ),
                    color = if (isClockedOut)
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            when {
                isClockedOut -> {
                    TextButton(onClick = {
                        scope.launch {
                            ws.resetTodaySession()
                            session = ws.getTodaySession()
                            onRefresh()
                        }
                    }) {
                        Text(
                            "Reset",
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                isClockedIn -> {
                    // Reset (accidental start)
                    TextButton(onClick = {
                        scope.launch {
                            ws.resetTodaySession()
                            session = ws.getTodaySession()
                            onRefresh()
                        }
                    }) {
                        Text(
                            "Reset",
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    TextButton(onClick = {
                        scope.launch {
                            val endMs = System.currentTimeMillis()
                            val startMs = session.actualStartMillis!!
                            ws.endShift(endMs)
                            ShiftCalendarSync.write(context, ws, startMs, endMs)
                            session = ws.getTodaySession()
                            onRefresh()
                        }
                    }) {
                        Text("End")
                    }
                }
                else -> {
                    TextButton(
                        onClick = {
                            val pressMs = System.currentTimeMillis()
                            if (isAfterScheduledStart && shiftStart != null) {
                                buttonPressMs = pressMs
                                dialog = StartDialog.ON_TIME
                            } else {
                                doStartShift(pressMs)
                            }
                        },
                        enabled = startEnabled
                    ) {
                        Text("Start")
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        if (!isClockedOut) Spacer(Modifier.height(2.dp))
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    // 1. Were you on time?
    if (dialog == StartDialog.ON_TIME) {
        AlertDialog(
            onDismissRequest = { dialog = StartDialog.NONE },
            title = { Text("Were you on time?") },
            text = {
                Text("Your shift was scheduled to start at ${shiftStart?.displayString}.")
            },
            confirmButton = {
                TextButton(onClick = {
                    shiftStart?.let {
                        val cal = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, it.hour)
                            set(Calendar.MINUTE, it.minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        doStartShift(cal.timeInMillis)
                    }
                    dialog = StartDialog.NONE
                }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { dialog = StartDialog.HOW_TO_LOG }) { Text("No") }
            }
        )
    }

    // 2. How to log?
    if (dialog == StartDialog.HOW_TO_LOG) {
        val pressTimeLabel = Calendar.getInstance().apply {
            timeInMillis = buttonPressMs
        }.let { "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }

        AlertDialog(
            onDismissRequest = { dialog = StartDialog.NONE },
            title = { Text("When did your shift start?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            doStartShift(buttonPressMs)
                            dialog = StartDialog.NONE
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Use $pressTimeLabel (when I tapped)")
                    }
                    TextButton(
                        onClick = { dialog = StartDialog.TIME_PICKER },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Set time manually")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { dialog = StartDialog.NONE }) { Text("Cancel") }
            }
        )
    }

    // 3. Time picker
    if (dialog == StartDialog.TIME_PICKER) {
        AlertDialog(
            onDismissRequest = { dialog = StartDialog.NONE },
            title = { Text("When did your shift start?") },
            text = {
                TimePicker(state = timePickerState)
            },
            confirmButton = {
                TextButton(onClick = {
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                        set(Calendar.MINUTE, timePickerState.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    doStartShift(cal.timeInMillis)
                    dialog = StartDialog.NONE
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { dialog = StartDialog.NONE }) { Text("Cancel") }
            }
        )
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
