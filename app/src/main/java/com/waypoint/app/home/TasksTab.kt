package com.waypoint.app.home

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waypoint.app.planner.BlockedEvent
import com.waypoint.app.planner.EventPlannerRegistry
import com.waypoint.app.planner.ScheduledEvent
import com.waypoint.app.planner.SleepCalendarSync
import com.waypoint.app.planner.SleepCheckReceiver
import com.waypoint.app.planner.SleepLogStore
import com.waypoint.app.planner.SleepModeState
import com.waypoint.app.planner.SleepScheduleStore
import com.waypoint.app.planner.TaskExecution
import com.waypoint.app.planner.TaskRequest
import com.waypoint.app.script.TaskManagerScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

// ── Tab root ──────────────────────────────────────────────────────────────────

@Composable
fun TasksTab(
    registry: EventPlannerRegistry,
    taskManager: TaskManagerScript,
    onRefresh: () -> Unit = {}
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<TaskRequest?>(null) }

    val plannerPlan = remember(refreshKey) { registry.planToday() }
    val scheduledTasks = remember(plannerPlan) {
        plannerPlan.scheduled.filter { it.event.sourceWidgetId == TaskManagerScript.WIDGET_ID }
    }
    val blockedTasks = remember(plannerPlan) {
        plannerPlan.blocked.filter { it.event.sourceWidgetId == TaskManagerScript.WIDGET_ID }
    }
    var doneIds by remember(refreshKey) { mutableStateOf(taskManager.completions.getDoneIds()) }
    val allTasks = remember(refreshKey) { taskManager.getAllTasks() }

    // Running execution — refreshed every 10 s for the elapsed-time display
    var runningExecution by remember { mutableStateOf(taskManager.getRunningExecution()) }
    var routineSubtaskIdx by remember { mutableIntStateOf(0) }
    var tickMs by remember { mutableStateOf(System.currentTimeMillis()) }

    val dayFmt = remember { DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()) }
    var headerClock by remember { mutableStateOf(clockNow()) }
    var headerDay by remember { mutableStateOf(LocalDate.now().format(dayFmt)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000L)
            tickMs = System.currentTimeMillis()
            headerClock = clockNow()
            headerDay = LocalDate.now().format(dayFmt)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ── Header bar: clock + day + Add button ──────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = headerDay,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { showAdd = true }) { Text("+ Add") }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SleepTaskRow(registry = registry, context = context, onRefresh = { refreshKey++; onRefresh() })

        val hasAny = scheduledTasks.isNotEmpty() || blockedTasks.isNotEmpty()
        if (!hasAny) {
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
                items(scheduledTasks, key = { "s_${it.event.id}" }) { se ->
                    val done = se.event.id in doneIds
                    val isRunning = runningExecution?.taskId == se.event.id
                    val taskReq = allTasks.find { it.id == se.event.id }
                    PlannerTaskRow(
                        se = se,
                        taskReq = taskReq,
                        done = done,
                        isRunning = isRunning,
                        routineSubtaskIdx = if (isRunning) routineSubtaskIdx else 0,
                        elapsedMs = if (isRunning) tickMs - (runningExecution!!.startMillis) else null,
                        onToggle = {
                            if (done) taskManager.unmarkDone(se.event.id)
                            else taskManager.markDone(se.event.id)
                            doneIds = taskManager.completions.getDoneIds()
                            onRefresh()
                        },
                        onStart = {
                            // Stop any existing running task first
                            runningExecution?.let { taskManager.stopExecution(it.taskId) }
                            val exec = taskManager.startExecution(se.event.id)
                            runningExecution = exec
                            routineSubtaskIdx = 0
                            taskReq?.subtasks?.firstOrNull()?.let { sub ->
                                taskManager.executions.startSubtask(se.event.id, sub.id)
                            }
                        },
                        onStop = {
                            // Stop active subtask if any
                            taskReq?.subtasks?.getOrNull(routineSubtaskIdx)?.let { sub ->
                                taskManager.executions.stopSubtask(se.event.id, sub.id)
                            }
                            val finished = taskManager.stopExecution(se.event.id)
                            runningExecution = null
                            if (finished != null) {
                                taskManager.markDone(se.event.id)
                                doneIds = taskManager.completions.getDoneIds()
                                taskManager.syncToRegistry()
                                refreshKey++
                                onRefresh()
                            }
                        },
                        onNextSubtask = {
                            val subtasks = taskReq?.subtasks ?: emptyList()
                            // Stop current subtask
                            subtasks.getOrNull(routineSubtaskIdx)?.let { sub ->
                                taskManager.executions.stopSubtask(se.event.id, sub.id)
                            }
                            val nextIdx = routineSubtaskIdx + 1
                            if (nextIdx < subtasks.size) {
                                subtasks[nextIdx].let { sub ->
                                    taskManager.executions.startSubtask(se.event.id, sub.id)
                                }
                                routineSubtaskIdx = nextIdx
                            } else {
                                // All subtasks done — auto-finish the task
                                val finished = taskManager.stopExecution(se.event.id)
                                runningExecution = null
                                if (finished != null) {
                                    taskManager.markDone(se.event.id)
                                    doneIds = taskManager.completions.getDoneIds()
                                    taskManager.syncToRegistry()
                                    refreshKey++
                                    onRefresh()
                                }
                            }
                        },
                        onEdit = { editTarget = allTasks.find { it.id == se.event.id } },
                        onDelete = {
                            if (isRunning) {
                                taskManager.stopExecution(se.event.id)
                                runningExecution = null
                            }
                            taskManager.retractTask(se.event.id)
                            refreshKey++
                            onRefresh()
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
                if (blockedTasks.isNotEmpty()) {
                    item {
                        Text(
                            text = "Unscheduled",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                    items(blockedTasks, key = { "b_${it.event.id}" }) { be ->
                        BlockedTaskRow(
                            be = be,
                            onEdit = { editTarget = allTasks.find { it.id == be.event.id } },
                            onDelete = {
                                taskManager.retractTask(be.event.id)
                                refreshKey++
                                onRefresh()
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
                val doneCount = scheduledTasks.count { it.event.id in doneIds }
                if (doneCount > 0) {
                    item {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = "$doneCount done",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }

    if (showAdd || editTarget != null) {
        AddTaskSheet(
            initial = editTarget,
            onDismiss = { showAdd = false; editTarget = null },
            onSave = { req ->
                taskManager.submitTask(req)
                refreshKey++
                onRefresh()
                showAdd = false
                editTarget = null
            }
        )
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

// ── Sleep task card ───────────────────────────────────────────────────────────

@Composable
private fun SleepTaskRow(
    registry: EventPlannerRegistry,
    context: Context,
    onRefresh: () -> Unit
) {
    val schedule = SleepScheduleStore(context).load()
    if (!schedule.enabled) return

    val logStore = remember { SleepLogStore(context) }
    val schedStore = remember { SleepScheduleStore(context) }
    val scope = rememberCoroutineScope()
    var sleepState by remember { mutableStateOf(logStore.getSleepModeState()) }
    var todayEntry by remember { mutableStateOf(logStore.loadToday()) }

    LaunchedEffect(Unit) {
        val logged = logStore.recordPhoneActive()
        sleepState = logStore.getSleepModeState()
        if (logged) {
            todayEntry = logStore.loadToday()
            val entry = todayEntry
            if (entry != null) {
                SleepCalendarSync.write(context, logStore, entry.bedMillis, entry.wakeMillis, null)
                todayEntry = logStore.loadToday()
            }
            withContext(Dispatchers.IO) { schedStore.syncToRegistry(registry) }
            onRefresh()
        }
    }

    val bedStr = schedule.preferredBedTime.displayString
    val wakeStr = schedule.preferredWakeTime.displayString

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$bedStr – $wakeStr",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val badgeLogged = todayEntry != null
            val badgeText = when {
                badgeLogged -> "Logged"
                sleepState == SleepModeState.SLEEPING -> "Sleeping"
                sleepState == SleepModeState.MONITORING -> "Monitoring"
                else -> "Sleep"
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = if (badgeLogged) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = badgeText,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (badgeLogged) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 14.dp)
        ) {
            when {
                todayEntry != null -> {
                    val entry = todayEntry!!
                    val durMins = ((entry.wakeMillis - entry.bedMillis) / 60_000L).toInt()
                    val h = durMins / 60; val m = durMins % 60
                    val durText = if (m == 0) "${h}h" else "${h}h ${m}m"
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Woke at ${formatShiftTime(entry.wakeMillis)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${formatShiftTime(entry.bedMillis)} – ${formatShiftTime(entry.wakeMillis)} · $durText",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        TextButton(onClick = {
                            scope.launch {
                                val eventId = todayEntry?.calendarEventId
                                logStore.clearToday()
                                if (eventId != null) {
                                    SleepCalendarSync.delete(context, eventId)
                                }
                                todayEntry = null
                                withContext(Dispatchers.IO) { schedStore.syncToRegistry(registry) }
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

                sleepState == SleepModeState.SLEEPING -> {
                    val startText = logStore.getSleepStartMillis()
                        ?.let { formatShiftTime(it) } ?: "--:--"
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Sleep detected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = "Onset · $startText",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        OutlinedButton(onClick = {
                            val sleepStart = logStore.getSleepStartMillis()
                            val now = System.currentTimeMillis()
                            val oldEventId = todayEntry?.calendarEventId
                            if (sleepStart != null && now > sleepStart) {
                                logStore.logManual(sleepStart, now)
                                scope.launch {
                                    val entry = logStore.loadToday()
                                    if (entry != null) {
                                        SleepCalendarSync.write(context, logStore, entry.bedMillis, entry.wakeMillis, oldEventId)
                                    }
                                    todayEntry = logStore.loadToday()
                                    withContext(Dispatchers.IO) { schedStore.syncToRegistry(registry) }
                                    onRefresh()
                                }
                            }
                            logStore.cancelSleepMode()
                            SleepCheckReceiver.cancel(context)
                            sleepState = SleepModeState.IDLE
                        }) {
                            Text("Done", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                sleepState == SleepModeState.MONITORING -> {
                    val sinceText = logStore.getSleepModeStartMillis()
                        ?.let { formatShiftTime(it) } ?: "--:--"
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Sleep mode active",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Monitoring since $sinceText",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        OutlinedButton(onClick = {
                            logStore.cancelSleepMode()
                            SleepCheckReceiver.cancel(context)
                            sleepState = SleepModeState.IDLE
                        }) {
                            Text("Cancel", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                else -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Bed at $bedStr",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = {
                            logStore.enterSleepMode()
                            SleepCheckReceiver.scheduleNextCheck(context)
                            sleepState = SleepModeState.MONITORING
                        }) {
                            Text("Sleep mode", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

// ── Planner task row (scheduled) ──────────────────────────────────────────────

@Composable
private fun PlannerTaskRow(
    se: ScheduledEvent,
    taskReq: TaskRequest?,
    done: Boolean,
    isRunning: Boolean,
    routineSubtaskIdx: Int,
    elapsedMs: Long?,
    onToggle: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onNextSubtask: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val rowBg = if (isRunning)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
    else Color.Transparent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RoundCheckbox(checked = done, onClick = if (!isRunning) onToggle else null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = se.event.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        textDecoration = if (done && !isRunning) TextDecoration.LineThrough else TextDecoration.None
                    ),
                    color = when {
                        isRunning -> primary
                        done -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                val isRoutine = taskReq?.isRoutine == true
                val subtasks = taskReq?.subtasks ?: emptyList()
                val subtitle = when {
                    isRunning && isRoutine && subtasks.isNotEmpty() -> {
                        val step = subtasks.getOrNull(routineSubtaskIdx)
                        "Step ${routineSubtaskIdx + 1}/${subtasks.size}" +
                            (step?.let { " · ${it.title}" } ?: "")
                    }
                    isRunning && elapsedMs != null -> {
                        val mins = (elapsedMs / 60_000L).toInt()
                        if (mins < 1) "Running · just started" else "Running · ${mins}m elapsed"
                    }
                    else -> "${formatShiftTime(se.startMillis)} – ${formatShiftTime(se.endMillis)} · ${se.event.durationMinutes}m"
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isRunning) primary.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                )
            }

            if (!done) {
                if (isRunning) {
                    val isRoutine = taskReq?.isRoutine == true
                    val subtasks = taskReq?.subtasks ?: emptyList()
                    val isLastStep = routineSubtaskIdx >= subtasks.size - 1

                    if (isRoutine && subtasks.isNotEmpty()) {
                        FilledTonalButton(
                            onClick = onNextSubtask,
                            modifier = Modifier.size(width = 72.dp, height = 32.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                if (isLastStep) "Finish" else "Next",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    } else {
                        FilledTonalButton(
                            onClick = onStop,
                            modifier = Modifier.size(width = 60.dp, height = 32.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Stop", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    IconButton(onClick = onStart, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Start timer",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit task",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Delete task",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                )
            }
        }
    }
}

// ── Blocked task row ──────────────────────────────────────────────────────────

@Composable
private fun BlockedTaskRow(be: BlockedEvent, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .border(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), CircleShape)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = be.event.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            )
            Text(
                text = be.reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit task",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Delete task",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
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
