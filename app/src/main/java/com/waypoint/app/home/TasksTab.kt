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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.waypoint.app.planner.ActiveBlockSession
import com.waypoint.app.planner.BlockSessionStore
import com.waypoint.app.planner.BlockTaskMeasurement
import com.waypoint.app.planner.BlockedEvent
import com.waypoint.app.planner.EventPlannerRegistry
import com.waypoint.app.planner.NamedBlock
import com.waypoint.app.planner.NamedBlockStore
import com.waypoint.app.planner.ScheduledEvent
import com.waypoint.app.planner.SleepCalendarSync
import com.waypoint.app.planner.SleepCheckReceiver
import com.waypoint.app.planner.SleepLogStore
import com.waypoint.app.planner.SleepModeState
import com.waypoint.app.planner.SleepScheduleStore
import com.waypoint.app.planner.TaskConditionSpec
import com.waypoint.app.planner.TaskExecution
import com.waypoint.app.planner.TaskRequest
import com.waypoint.app.planner.TriggerEvent
import com.waypoint.app.script.TaskManagerScript
import com.waypoint.app.signal.CalendarEvent
import com.waypoint.app.signal.CalendarSignals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

// ── Tab root ──────────────────────────────────────────────────────────────────

@Composable
fun TasksTab(
    registry: EventPlannerRegistry,
    taskManager: TaskManagerScript,
    calendarSignals: CalendarSignals? = null,
    blockSessionStore: BlockSessionStore? = null,
    availableBlocks: List<NamedBlock> = emptyList(),
    onRefresh: () -> Unit = {}
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var todayCalEvents by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    LaunchedEffect(Unit) {
        if (calendarSignals?.hasPermission() == true) {
            todayCalEvents = calendarSignals.eventsForDate(LocalDate.now())
        }
    }
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
    // Tasks that are the target of at least one chain trigger from another task
    val chainTargetIds = remember(allTasks) {
        allTasks.flatMap { it.triggers }.map { it.chainTaskId }.toSet()
    }

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

    val namedBlockStore = remember { NamedBlockStore(context) }
    val noSession = remember { kotlinx.coroutines.flow.MutableStateFlow<ActiveBlockSession?>(null) }
    val activeSession by (blockSessionStore?.sessionFlow ?: noSession).collectAsState()
    val today = remember { LocalDate.now() }

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

        // Block session cards
        if (blockSessionStore != null) {
            val sess = activeSession
            if (sess != null) {
                BlockSessionCard(
                    session = sess,
                    namedBlockStore = namedBlockStore,
                    blockSessionStore = blockSessionStore,
                    taskManager = taskManager
                )
            } else {
                val todayBlocks = remember(refreshKey) { namedBlockStore.resolveForDate(today) }
                todayBlocks.forEach { (block, sched) ->
                    val startMs = today.atTime(sched.startHour, sched.startMinute)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val endMs = if (sched.endHour >= 0) {
                        val e = today.atTime(sched.endHour, sched.endMinute)
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        if (e > startMs) e else e + 24 * 3600_000L
                    } else startMs + block.estimatedMinutes * 60_000L
                    BlockStartCard(
                        blockName = block.name,
                        colorArgb = block.colorArgb,
                        startMs = startMs,
                        endMs = endMs,
                        onStart = { blockSessionStore.startSession(block, endMs, today) }
                    )
                }
            }
        }

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
                        hasIncomingChain = se.event.id in chainTargetIds,
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
                            applyTriggers(TriggerEvent.TASK_STARTED, se.event.id, allTasks, taskManager)
                            // Pin the started task in the registry so it stops sliding
                            taskManager.syncToRegistry()
                            refreshKey++
                            onRefresh()
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
                                applyTriggers(TriggerEvent.TASK_COMPLETED, se.event.id, allTasks, taskManager)
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
                                    applyTriggers(TriggerEvent.TASK_COMPLETED, se.event.id, allTasks, taskManager)
                                    doneIds = taskManager.completions.getDoneIds()
                                    taskManager.syncToRegistry()
                                    refreshKey++
                                    onRefresh()
                                }
                            }
                        },
                        onEdit = { editTarget = allTasks.find { it.id == se.event.id } },
                        onSkip = {
                            if (isRunning) {
                                taskManager.stopExecution(se.event.id)
                                runningExecution = null
                            }
                            taskManager.skipTask(se.event.id)
                            refreshKey++
                            onRefresh()
                        },
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
                            taskTitle = allTasks.find { it.id == be.event.id }?.title ?: be.event.title,
                            hasChainTriggers = allTasks.find { it.id == be.event.id }?.triggers?.isNotEmpty() == true,
                            onEdit = { editTarget = allTasks.find { it.id == be.event.id } },
                            onSkip = {
                                taskManager.skipTask(be.event.id)
                                refreshKey++
                                onRefresh()
                            },
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
            availableTasks = allTasks,
            calendarEvents = todayCalEvents,
            availableBlocks = availableBlocks,
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

// ── Block start card (no active session) ─────────────────────────────────────

@Composable
private fun BlockStartCard(
    blockName: String,
    colorArgb: Int?,
    startMs: Long,
    endMs: Long,
    onStart: () -> Unit
) {
    val accentColor = colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val timeFmt = "%02d:%02d".format(
        Calendar.getInstance().apply { timeInMillis = startMs }.get(Calendar.HOUR_OF_DAY),
        Calendar.getInstance().apply { timeInMillis = startMs }.get(Calendar.MINUTE)
    ) + " – " + "%02d:%02d".format(
        Calendar.getInstance().apply { timeInMillis = endMs }.get(Calendar.HOUR_OF_DAY),
        Calendar.getInstance().apply { timeInMillis = endMs }.get(Calendar.MINUTE)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(48.dp)
                .background(accentColor, RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = blockName,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = timeFmt,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onStart) { Text("▶ Start") }
    }
}

// ── Block session card (active session) ───────────────────────────────────────

@Composable
private fun BlockSessionCard(
    session: ActiveBlockSession,
    namedBlockStore: NamedBlockStore,
    blockSessionStore: BlockSessionStore,
    taskManager: TaskManagerScript
) {
    val context = LocalContext.current
    val accentColor = session.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val today = remember { LocalDate.now() }

    var completionMode by remember { mutableStateOf(false) }

    val activeTasks = remember(session.blockId) {
        namedBlockStore.resolveActiveTasks(session.blockId, today)
    }

    val doneIds = remember { taskManager.completions.getDoneIds() }
    val checkState = remember(activeTasks) {
        mutableStateMapOf<String, Boolean>().also { map ->
            activeTasks.forEach { task -> map[task.id] = task.id in doneIds }
        }
    }

    // Countdown: remaining time, updated every minute
    var remainingMs by remember { mutableStateOf(session.scheduledEndMs - System.currentTimeMillis()) }
    LaunchedEffect(session.scheduledEndMs) {
        while (true) {
            delay(60_000L)
            remainingMs = session.scheduledEndMs - System.currentTimeMillis()
        }
    }

    // Task timer state for useMeasuredDuration tasks
    var runningBlockTaskId by remember { mutableStateOf<String?>(null) }
    var blockTaskStartMs by remember { mutableStateOf(0L) }
    var elapsedTick by remember { mutableIntStateOf(0) }
    val sessionMeasurements = remember { mutableStateListOf<BlockTaskMeasurement>() }
    LaunchedEffect(runningBlockTaskId) {
        elapsedTick = 0
        while (runningBlockTaskId != null) {
            delay(1_000L)
            elapsedTick++
        }
    }

    // Compute next occurrence (within 7 days)
    val nextOccurrenceDate = remember(session.blockId) {
        (1..7).map { today.plusDays(it.toLong()) }
            .firstOrNull { namedBlockStore.resolveForDate(it).any { (b, _) -> b.id == session.blockId } }
    }

    val startedStr = "%02d:%02d".format(
        Calendar.getInstance().apply { timeInMillis = session.startedAtMs }.get(Calendar.HOUR_OF_DAY),
        Calendar.getInstance().apply { timeInMillis = session.startedAtMs }.get(Calendar.MINUTE)
    )
    val endStr = "%02d:%02d".format(
        Calendar.getInstance().apply { timeInMillis = session.scheduledEndMs }.get(Calendar.HOUR_OF_DAY),
        Calendar.getInstance().apply { timeInMillis = session.scheduledEndMs }.get(Calendar.MINUTE)
    )
    val countdownStr = run {
        val totalMins = (remainingMs / 60_000L).toInt().coerceAtLeast(0)
        val h = totalMins / 60
        val m = totalMins % 60
        when {
            h > 0 && m > 0 -> "ends in ${h}h ${m}m"
            h > 0           -> "ends in ${h}h"
            m > 0           -> "ends in ${m}m"
            else            -> "ending now"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(56.dp)
                    .background(accentColor, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(
                    text = session.blockName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (completionMode) "Plan for next occurrence"
                           else "started $startedStr · $countdownStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            if (!completionMode) {
                TextButton(onClick = { blockSessionStore.extendSession(30 * 60_000L) }) {
                    Text("+ 30m", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { completionMode = true }) {
                    Text("End", color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        if (!completionMode) {
            // Active mode: show today's tasks as informational checklist
            val sectionLabel = "Set tasks for today"
            Text(
                text = sectionLabel,
                style = MaterialTheme.typography.labelSmall,
                color = accentColor.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (activeTasks.isEmpty()) {
                Text(
                    "No tasks configured for this block",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            } else {
                activeTasks.forEach { task ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val wasChecked = checkState[task.id] == true
                                checkState[task.id] = !wasChecked
                                if (!wasChecked) taskManager.markDone(task.id)
                                else taskManager.unmarkDone(task.id)
                            }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val checked = checkState[task.id] == true
                        RoundCheckbox(checked = checked, onClick = {
                            val wasChecked = checkState[task.id] == true
                            checkState[task.id] = !wasChecked
                            if (!wasChecked) taskManager.markDone(task.id)
                            else taskManager.unmarkDone(task.id)
                        })
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        if (task.useMeasuredDuration) {
                            val isRunning = runningBlockTaskId == task.id
                            if (isRunning) {
                                val elapsedS = elapsedTick.let {
                                    ((System.currentTimeMillis() - blockTaskStartMs) / 1000L).toInt()
                                }
                                Text(
                                    text = "%d:%02d".format(elapsedS / 60, elapsedS % 60),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accentColor
                                )
                                IconButton(
                                    onClick = {
                                        val endMs = System.currentTimeMillis()
                                        sessionMeasurements.add(BlockTaskMeasurement(
                                            taskId = task.id,
                                            startMs = blockTaskStartMs,
                                            endMs = endMs
                                        ))
                                        runningBlockTaskId = null
                                        blockTaskStartMs = 0L
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Stop timer",
                                        modifier = Modifier.size(16.dp),
                                        tint = accentColor
                                    )
                                }
                            } else {
                                Text(
                                    text = "${task.durationMinutes}m",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                IconButton(
                                    onClick = {
                                        // Auto-stop any currently running task first
                                        val prevId = runningBlockTaskId
                                        if (prevId != null) {
                                            sessionMeasurements.add(BlockTaskMeasurement(
                                                taskId = prevId,
                                                startMs = blockTaskStartMs,
                                                endMs = System.currentTimeMillis()
                                            ))
                                        }
                                        runningBlockTaskId = task.id
                                        blockTaskStartMs = System.currentTimeMillis()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = "Start timer",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "${task.durationMinutes}m",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        } else {
            // Completion mode: plan tasks for next occurrence
            val nextDate = nextOccurrenceDate
            if (nextDate == null) {
                Text(
                    "No upcoming occurrence found",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { completionMode = false }) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        val finalMeasurements = if (runningBlockTaskId != null) {
                            sessionMeasurements + BlockTaskMeasurement(
                                taskId = runningBlockTaskId!!,
                                startMs = blockTaskStartMs,
                                endMs = System.currentTimeMillis()
                            )
                        } else sessionMeasurements.toList()
                        blockSessionStore.endSession(
                            tasksCompleted = checkState.values.count { it },
                            tasksTotal = activeTasks.size,
                            taskMeasurements = finalMeasurements
                        )
                    }) {
                        Text("Exit timeblock")
                    }
                }
            } else {
                val dateFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
                Text(
                    text = "Set tasks for ${nextDate.format(dateFmt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor.copy(alpha = 0.8f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                val allBlockTasks = remember(session.blockId) { namedBlockStore.loadTasksForBlock(session.blockId) }
                val nextActivation = remember(session.blockId, nextDate) { namedBlockStore.getActivation(session.blockId, nextDate) }
                val nextToggleState = remember(nextActivation) {
                    mutableStateMapOf<String, Boolean>().also { map ->
                        allBlockTasks.forEach { task ->
                            map[task.id] = task.isAlways || task.id in nextActivation.activeTaskIds
                        }
                    }
                }
                allBlockTasks.forEach { task ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (!task.isAlways) Modifier.clickable {
                                nextToggleState[task.id] = !(nextToggleState[task.id] ?: false)
                            } else Modifier)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val checked = nextToggleState[task.id] == true
                        RoundCheckbox(
                            checked = checked,
                            onClick = if (!task.isAlways) ({ nextToggleState[task.id] = !checked }) else null
                        )
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (task.isAlways) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${task.durationMinutes}m" + if (task.isAlways) " · always" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { completionMode = false }) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        // Save situational task activations for next date
                        allBlockTasks.filter { !it.isAlways }.forEach { task ->
                            val shouldBeActive = nextToggleState[task.id] == true
                            val isCurrentlyActive = task.id in nextActivation.activeTaskIds
                            if (shouldBeActive != isCurrentlyActive) {
                                namedBlockStore.toggleSituational(session.blockId, nextDate, task.id)
                            }
                        }
                        val finalMeasurements = if (runningBlockTaskId != null) {
                            sessionMeasurements + BlockTaskMeasurement(
                                taskId = runningBlockTaskId!!,
                                startMs = blockTaskStartMs,
                                endMs = System.currentTimeMillis()
                            )
                        } else sessionMeasurements.toList()
                        blockSessionStore.endSession(
                            tasksCompleted = checkState.values.count { it },
                            tasksTotal = activeTasks.size,
                            taskMeasurements = finalMeasurements
                        )
                    }) {
                        Text("Save & exit timeblock", color = accentColor)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
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
    hasIncomingChain: Boolean = false,
    onToggle: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onNextSubtask: () -> Unit,
    onEdit: () -> Unit,
    onSkip: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    if (showDeleteDialog) {
        TaskDeleteDialog(
            taskTitle = se.event.title,
            hasChainTriggers = taskReq?.triggers?.isNotEmpty() == true,
            onSkip = onSkip,
            onDelete = onDelete,
            onDismiss = { showDeleteDialog = false }
        )
    }
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
                val chainSuffix = if (hasIncomingChain && !isRunning) " · chained" else ""
                Text(
                    text = subtitle + chainSuffix,
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
            IconButton(onClick = { showDeleteDialog = true }) {
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
private fun BlockedTaskRow(
    be: BlockedEvent,
    taskTitle: String = be.event.title,
    hasChainTriggers: Boolean = false,
    onEdit: () -> Unit,
    onSkip: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    if (showDeleteDialog) {
        TaskDeleteDialog(
            taskTitle = taskTitle,
            hasChainTriggers = hasChainTriggers,
            onSkip = onSkip,
            onDelete = onDelete,
            onDismiss = { showDeleteDialog = false }
        )
    }
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
        IconButton(onClick = { showDeleteDialog = true }) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Delete task",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
            )
        }
    }
}

// ── Trigger engine ────────────────────────────────────────────────────────────

private fun applyTriggers(
    event: TriggerEvent,
    sourceTaskId: String,
    allTasks: List<TaskRequest>,
    taskManager: TaskManagerScript
) {
    val source = allTasks.find { it.id == sourceTaskId } ?: return
    val now = System.currentTimeMillis()
    source.triggers
        .filter { it.event == event }
        .forEach { trigger ->
            val target = allTasks.find { it.id == trigger.chainTaskId } ?: return@forEach
            val updatedConditions = if (trigger.deadlineMinutes > 0) {
                val byMs = now + trigger.deadlineMinutes * 60_000L
                target.conditions.filter { it.type != "deadline" } +
                    TaskConditionSpec("deadline", deadlineMillis = byMs)
            } else {
                target.conditions
            }
            taskManager.submitTask(target.copy(conditions = updatedConditions))
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
