package com.waypoint.app.home

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

@Composable
fun TasksTab(workSchedule: WorkScheduleSignals) {
    val context = LocalContext.current
    val store = remember { TaskStore(context) }

    var tasks by remember { mutableStateOf(store.loadToday()) }
    var input by remember { mutableStateOf("") }

    fun submit() {
        val title = input.trim()
        if (title.isNotEmpty()) {
            tasks = store.add(title)
            input = ""
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ── Add task row ─────────────────────────────────────────────────────
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
                placeholder = {
                    Text("Add a task…", style = MaterialTheme.typography.bodyMedium)
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() })
            )
            TextButton(
                onClick = { submit() },
                enabled = input.isNotBlank()
            ) {
                Text("Add")
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // ── Shift task (auto, work days only) ────────────────────────────────
        ShiftTaskRow(ws = workSchedule, context = context)

        // ── Task list ────────────────────────────────────────────────────────
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
                        onToggle = { tasks = store.setDone(task.id, !task.done) },
                        onDelete = { tasks = store.delete(task.id) }
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
                    TextButton(onClick = { tasks = store.clearCompleted() }) {
                        Text("Clear completed", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

// ── Shift task row ────────────────────────────────────────────────────────────

@Composable
private fun ShiftTaskRow(ws: WorkScheduleSignals, context: Context) {
    val schedule = remember { ws.getTodaySchedule() }
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

    val title = when {
        isClockedOut -> "Shift complete"
        isClockedIn -> {
            val endLabel = shiftEnd?.let { e ->
                val diff = e.totalMinutes - nowMinutes
                val adjDiff = if (diff < 0) diff + 1440 else diff
                when {
                    adjDiff <= 0 -> "ending soon"
                    adjDiff < 60 -> "ends in ${adjDiff}m"
                    else -> "ends in ${adjDiff / 60}h ${adjDiff % 60}m"
                }
            }
            if (endLabel != null) "Shift in progress · $endLabel" else "Shift in progress"
        }
        shiftStart != null -> {
            val diff = shiftStart.totalMinutes - nowMinutes
            when {
                diff <= 0 -> "Work · start shift"
                diff < 60 -> "Work in ${diff}m"
                else -> "Work in ${diff / 60}h ${diff % 60}m"
            }
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

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isClockedOut,
                onCheckedChange = null,
                enabled = false
            )
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
            if (!isClockedOut) {
                TextButton(onClick = {
                    scope.launch {
                        if (isClockedIn) {
                            val endMs = System.currentTimeMillis()
                            val startMs = session.actualStartMillis!!
                            ws.endShift(endMs)
                            ShiftCalendarSync.write(context, ws, startMs, endMs)
                        } else {
                            ws.startShift()
                        }
                        session = ws.getTodaySession()
                    }
                }) {
                    Text(if (isClockedIn) "End" else "Start")
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        if (!isClockedOut) Spacer(Modifier.height(2.dp))
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
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = task.done,
            onCheckedChange = { onToggle() }
        )
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
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            )
        }
    }
}
