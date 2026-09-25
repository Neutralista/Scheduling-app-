package com.waypoint.app.home

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
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
import com.waypoint.app.WaypointApplication
import com.waypoint.app.integration.openTrainingAppWorkout
import com.waypoint.app.integration.trainingAppWorkoutId
import com.waypoint.app.planner.ActiveBlockSession
import com.waypoint.app.planner.BlockSessionStore
import com.waypoint.app.planner.BlockSessionLogStore
import com.waypoint.app.planner.BlockTaskMeasurement
import com.waypoint.app.planner.BlockTaskPlacement
import com.waypoint.app.planner.phaseWindows
import com.waypoint.app.planner.phaseOf
import com.waypoint.app.planner.phaseLengthMinutes
import com.waypoint.app.planner.currentPhase
import com.waypoint.app.planner.BlockPhase
import com.waypoint.app.planner.BlockTask
import com.waypoint.app.planner.BlockedEvent
import com.waypoint.app.planner.EventPlannerRegistry
import com.waypoint.app.planner.NamedBlock
import com.waypoint.app.planner.NamedBlockSchedule
import com.waypoint.app.planner.NamedBlockStore
import com.waypoint.app.planner.occursOn
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
import com.waypoint.app.ui.components.TimePickerDialog
import com.waypoint.app.ui.components.toOpaqueColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.style.TextOverflow
import com.waypoint.app.planner.EventCategory
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

    val today = remember { LocalDate.now() }
    val namedBlockStore = remember { NamedBlockStore(context) }
    // Resolved once here and reused both for the plan (so tasks don't get scheduled
    // into time a block already occupies) and for the block-start cards below —
    // previously this tab called planToday() with no block info at all, so its task
    // list could disagree with the Plan tab's timeline about what's happening when.
    val todayFixedBlocks = remember(refreshKey) { namedBlockStore.resolveFixedInstancesForDate(today) }
    val todayFloatingBlocks = remember(refreshKey) { namedBlockStore.resolveFloatingInstancesForDate(today) }

    val plannerPlan = remember(refreshKey, todayFixedBlocks, todayFloatingBlocks) {
        registry.planToday(namedBlockInstances = todayFixedBlocks, floatingBlocks = todayFloatingBlocks)
    }
    // todayFloatingBlocks carries placeholder 0L/0L times (floating blocks have no fixed slot
    // until the planner places them) — resolve each one's real window from the plan output so
    // it can get a start card too. Previously this list was never consulted for start cards at
    // all, so a floating ("auto-place") block never showed one, however it was scheduled today.
    val todayScheduledFloatingBlocks = remember(plannerPlan, todayFloatingBlocks) {
        todayFloatingBlocks.mapNotNull { inst ->
            val se = plannerPlan.scheduled.find { it.event.id == "__block__${inst.block.id}" }
            se?.let { inst.copy(scheduledStartMs = it.startMillis, estimatedEndMs = it.endMillis) }
        }
    }
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

    // Running execution, and the clock the countdowns run on: every second while a timer runs
    // (it shows seconds), otherwise every 15 s (the countdowns show minutes).
    var runningExecution by remember { mutableStateOf(taskManager.getRunningExecution()) }
    var tickMs by remember { mutableStateOf(System.currentTimeMillis()) }

    val dayFmt = remember { DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()) }
    var headerDay by remember { mutableStateOf(LocalDate.now().format(dayFmt)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(if (runningExecution != null) 1_000L else 15_000L)
            tickMs = System.currentTimeMillis()
            headerDay = LocalDate.now().format(dayFmt)
        }
    }

    // The to-do list: every task scheduled today, plain ones and those inside blocks, in the
    // order they're planned. Skipped ones are listed separately below.
    val blockNames = remember(plannerPlan) {
        plannerPlan.scheduled.filter { it.event.category == EventCategory.BLOCK }
            .associate { it.event.id to it.event.title }
    }
    val blockColors = remember(refreshKey) {
        namedBlockStore.loadAllBlocks().associate { "__block__${it.id}" to it.colorArgb }
    }
    val todoItems = remember(plannerPlan, refreshKey) {
        plannerPlan.scheduled
            .filter { se ->
                se.event.sourceWidgetId == TaskManagerScript.WIDGET_ID ||
                    se.event.sourceWidgetId?.startsWith("__block__") == true
            }
            .filter { !taskManager.isSkipped(it.event.id) }
            .sortedBy { it.startMillis }
    }
    val openItems = todoItems.filter { it.event.id !in doneIds }
    val doneItems = todoItems.filter { it.event.id in doneIds }
    var showDone by remember { mutableStateOf(false) }

    val noSession = remember { kotlinx.coroutines.flow.MutableStateFlow<ActiveBlockSession?>(null) }
    val activeSession by (blockSessionStore?.sessionFlow ?: noSession).collectAsState()

    // ── Blocks already run or skipped today, and Reset ──
    val blockLogStore = remember { BlockSessionLogStore(context) }
    // Re-read when a session ends, too: it's just been logged.
    val blockLogsToday = remember(refreshKey, activeSession) {
        blockLogStore.loadAll().filter { it.date == today.toString() }.groupBy { it.blockId }
    }
    val skippedBlocksToday = remember(refreshKey) {
        namedBlockStore.loadAllBlocks().filter { it.enabled && namedBlockStore.isSkippedForDate(it.id, today) }
    }
    /**
     * Like sleep's Reset: forgets today's run of a block — its logged session(s), the ticks on
     * its tasks — and a skip, so it's back to planned for today.
     */
    fun resetBlockToday(blockId: String) {
        blockLogStore.loadAll()
            .filter { it.blockId == blockId && it.date == today.toString() }
            .forEach { blockLogStore.deleteEntry(blockId, it.startedAtMs) }
        namedBlockStore.resolveActiveTasks(blockId, today).forEach { task ->
            if (taskManager.isDone(task.id)) taskManager.unmarkDone(task.id)
            if (taskManager.isSkipped(task.id)) taskManager.unskipTask(task.id)
        }
        namedBlockStore.unskipForDate(blockId, today)
        doneIds = taskManager.completions.getDoneIds()
        refreshKey++
        onRefresh()
    }

    // ── Row actions ──
    fun isPlain(se: ScheduledEvent) = se.event.sourceWidgetId == TaskManagerScript.WIDGET_ID
    fun toggle(se: ScheduledEvent) {
        if (se.event.id in doneIds) taskManager.unmarkDone(se.event.id)
        else {
            taskManager.markDone(se.event.id)
            if (isPlain(se)) applyTriggers(TriggerEvent.TASK_COMPLETED, se.event.id, allTasks, taskManager)
        }
        doneIds = taskManager.completions.getDoneIds()
        refreshKey++
        onRefresh()
    }
    fun startTimer(se: ScheduledEvent) {
        runningExecution?.let { taskManager.stopExecution(it.taskId) }
        runningExecution = taskManager.startExecution(se.event.id)
        tickMs = System.currentTimeMillis()
        applyTriggers(TriggerEvent.TASK_STARTED, se.event.id, allTasks, taskManager)
        // Pin the started task in the registry so it stops sliding
        taskManager.syncToRegistry()
        refreshKey++
        onRefresh()
    }
    fun stopTimer(se: ScheduledEvent) {
        val finished = taskManager.stopExecution(se.event.id)
        runningExecution = null
        if (finished != null) {
            taskManager.markDone(se.event.id)
            applyTriggers(TriggerEvent.TASK_COMPLETED, se.event.id, allTasks, taskManager)
            doneIds = taskManager.completions.getDoneIds()
            refreshKey++
            onRefresh()
        }
    }
    fun stopIfRunning(se: ScheduledEvent) {
        if (runningExecution?.taskId == se.event.id) {
            taskManager.stopExecution(se.event.id)
            runningExecution = null
        }
    }
    fun skip(se: ScheduledEvent) {
        stopIfRunning(se)
        taskManager.skipTask(se.event.id)
        refreshKey++
        onRefresh()
    }
    fun delete(se: ScheduledEvent) {
        stopIfRunning(se)
        taskManager.retractTask(se.event.id)
        refreshKey++
        onRefresh()
    }


    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        // ── Header: what's left today, and Add ────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "To do",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "$headerDay · ${openItems.size} left · ${doneItems.size} done",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(onClick = { showAdd = true }) { Text("+ Add") }
        }
        if (todoItems.isNotEmpty()) {
            val fraction = doneItems.size.toFloat() / todoItems.size
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 10.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            ) {
                Box(
                    Modifier.fillMaxWidth(fraction).height(4.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
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
                    taskManager = taskManager,
                    onRefresh = onRefresh
                )
            } else {
                // A block already done today shows when it ran, with Reset; one not yet, Start.
                (todayFixedBlocks + todayScheduledFloatingBlocks).forEach { inst ->
                    val ran = blockLogsToday[inst.block.id]
                    if (ran != null) {
                        BlockStartCard(
                            blockName = inst.block.name,
                            colorArgb = inst.block.colorArgb,
                            startMs = ran.minOf { it.startedAtMs },
                            endMs = ran.maxOf { it.endedAtMs },
                            statusPrefix = "Done ",
                            actionLabel = "Reset",
                            actionIsReset = true,
                            onStart = { resetBlockToday(inst.block.id) }
                        )
                    } else {
                        BlockStartCard(
                            blockName = inst.block.name,
                            colorArgb = inst.block.colorArgb,
                            startMs = inst.scheduledStartMs,
                            endMs = inst.estimatedEndMs,
                            onStart = {
                                val now = System.currentTimeMillis()
                                blockSessionStore.startSession(inst.block, now + namedBlockStore.plannedDurationMs(inst.block, today), today, now)
                            }
                        )
                    }
                }
                // Skipped today: Reset puts it back.
                skippedBlocksToday.forEach { block ->
                    BlockStartCard(
                        blockName = block.name,
                        colorArgb = block.colorArgb,
                        startMs = null,
                        endMs = null,
                        statusPrefix = "Skipped today",
                        actionLabel = "Reset",
                        actionIsReset = true,
                        onStart = { resetBlockToday(block.id) }
                    )
                }
            }
        }

        ScheduledBlocksDropdown(namedBlockStore = namedBlockStore, refreshKey = refreshKey)

        // Skipped tasks leave the plan, so they're listed from the queue to be un-skipped.
        val skippedTasks = remember(refreshKey, allTasks) { allTasks.filter { taskManager.isSkipped(it.id) } }
        val hasAny = todoItems.isNotEmpty() || blockedTasks.isNotEmpty() || skippedTasks.isNotEmpty()
        if (!hasAny) {
            // Tapping anywhere in the empty list adds a task.
            Column(
                modifier = Modifier.fillMaxSize().clickable { showAdd = true },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Nothing to do today",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tap here to add a task",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            @Composable
            fun TodoItem(se: ScheduledEvent) {
                val plain = isPlain(se)
                val taskReq = if (plain) allTasks.find { it.id == se.event.id } else null
                val running = runningExecution?.taskId == se.event.id
                TodoRow(
                    se = se,
                    blockName = se.event.sourceWidgetId?.let { blockNames[it] },
                    blockColor = se.event.sourceWidgetId?.let { blockColors[it] }?.toOpaqueColor(),
                    done = se.event.id in doneIds,
                    runningSinceMs = if (running) runningExecution?.startMillis else null,
                    nowMs = tickMs,
                    chained = se.event.id in chainTargetIds,
                    measured = taskReq?.useMeasuredDuration == true,
                    onToggle = { toggle(se) },
                    onStart = if (plain) { { startTimer(se) } } else null,
                    onStop = { stopTimer(se) },
                    onEdit = taskReq?.let { req -> { editTarget = req } },
                    onSkip = { skip(se) },
                    onDelete = if (plain) { { delete(se) } } else null,
                    onMarkDoneAt = { whenMs ->
                        taskManager.markDoneAt(se.event.id, whenMs)
                        doneIds = taskManager.completions.getDoneIds()
                        refreshKey++
                        onRefresh()
                    },
                    onLogPastExecution = { startMs, endMs ->
                        taskManager.logPastExecution(se.event.id, startMs, endMs)
                        doneIds = taskManager.completions.getDoneIds()
                        refreshKey++
                        onRefresh()
                    }
                )
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (openItems.isEmpty() && todoItems.isNotEmpty()) {
                    item(key = "all_done") {
                        Text(
                            "All done for today",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
                        )
                    }
                }
                items(openItems, key = { "s_${it.event.id}" }) { se -> TodoItem(se) }
                if (doneItems.isNotEmpty()) {
                    item(key = "done_header") {
                        TodoSectionHeader(
                            title = "Done (${doneItems.size})",
                            expanded = showDone,
                            onClick = { showDone = !showDone }
                        )
                    }
                    if (showDone) items(doneItems, key = { "d_${it.event.id}" }) { se -> TodoItem(se) }
                }
                if (blockedTasks.isNotEmpty()) {
                    item(key = "unscheduled_header") { TodoSectionHeader(title = "Unscheduled (${blockedTasks.size})") }
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
                    }
                }
                if (skippedTasks.isNotEmpty()) {
                    item(key = "skipped_header") { TodoSectionHeader(title = "Skipped (${skippedTasks.size})") }
                    items(skippedTasks, key = { "k_${it.id}" }) { task ->
                        SkippedTaskRow(
                            title = task.title,
                            onUnskip = {
                                taskManager.unskipTask(task.id)
                                refreshKey++
                                onRefresh()
                            }
                        )
                    }
                }
                // The free space under the list: tapping it adds a task.
                item(key = "add_space") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillParentMaxHeight(0.5f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showAdd = true },
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Text(
                            "+ Tap to add a task",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 16.dp)
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
            eventPlanner = registry,
            namedBlockStore = namedBlockStore,
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
    startMs: Long?,
    endMs: Long?,
    onStart: () -> Unit,
    /** Before the times ("Done "), or the whole status when there are none ("Skipped today"). */
    statusPrefix: String = "",
    actionLabel: String = "▶ Start",
    actionIsReset: Boolean = false
) {
    val accentColor = colorArgb?.toOpaqueColor() ?: MaterialTheme.colorScheme.primary
    val timeFmt = statusPrefix + if (startMs != null && endMs != null) {
        formatShiftTime(startMs) + " – " + formatShiftTime(endMs)
    } else ""
    var confirmReset by remember { mutableStateOf(false) }
    if (confirmReset) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset $blockName for today?") },
            text = { Text("Its logged session, ticked tasks and any skip today are cleared, so it's back to planned.") },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; onStart() }) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
        )
    }
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
        if (actionIsReset) {
            TextButton(onClick = { confirmReset = true }) {
                Text(actionLabel, color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
            }
        } else {
            TextButton(onClick = onStart) { Text(actionLabel) }
        }
    }
}

// ── Block session card (active session) ───────────────────────────────────────

/** A heading in the session checklist and its tasks; [isCurrent] marks the phase that's on. */
private data class TaskGroup(val label: String, val tasks: List<BlockTask>, val isCurrent: Boolean = false)

@Composable
private fun BlockSessionCard(
    session: ActiveBlockSession,
    namedBlockStore: NamedBlockStore,
    blockSessionStore: BlockSessionStore,
    taskManager: TaskManagerScript,
    onRefresh: () -> Unit = {}
) {
    val context = LocalContext.current
    val accentColor = session.colorArgb?.toOpaqueColor() ?: MaterialTheme.colorScheme.primary
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
    var backdateTarget by remember { mutableStateOf<String?>(null) }
    // Block sub-tasks share the same completion store as flat tasks (EventPlannerRegistry
    // synthesizes their planner event id as the BlockTask's own id), so toggling here has to
    // persist through taskManager — otherwise this checklist's state is invisible everywhere
    // else in the app, including the header's daily progress bar.
    fun toggleTask(taskId: String) {
        val next = !(checkState[taskId] ?: false)
        checkState[taskId] = next
        if (next) taskManager.markDone(taskId) else taskManager.unmarkDone(taskId)
        onRefresh()
    }

    fun skipBlockTask(taskId: String) {
        taskManager.skipTask(taskId)
        onRefresh()
    }

    // Countdown: remaining time, updated every minute
    var remainingMs by remember { mutableStateOf(session.scheduledEndMs - System.currentTimeMillis()) }
    LaunchedEffect(session.scheduledEndMs) {
        while (true) {
            delay(60_000L)
            remainingMs = session.scheduledEndMs - System.currentTimeMillis()
        }
    }

    // ── Phases ──────────────────────────────────────────────────────────────
    val sessionBlock = remember(session.blockId) { namedBlockStore.loadBlock(session.blockId) }
    var nowForPhase by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session) {
        while (true) {
            nowForPhase = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    val currentPhase = sessionBlock?.let {
        currentPhase(it, activeTasks, session.startedAtMs, session.scheduledEndMs, session.phaseStarts, nowForPhase)
    }
    // Next phase after the current one; with none current, the first not yet started.
    val nextPhase = sessionBlock?.phases?.let { phases ->
        if (currentPhase != null) phases.getOrNull(phases.indexOf(currentPhase) + 1)
        else phases.firstOrNull { it.id !in session.phaseStarts }
    }
    // When the current phase ends: its planned window, or from when Next phase started it.
    val currentPhaseEndMs = currentPhase?.let { phase ->
        session.phaseStarts[phase.id]?.let { it + phaseLengthMinutes(phase, activeTasks) * 60_000L }
            ?: sessionBlock?.let { b ->
                phaseWindows(b, activeTasks, session.startedAtMs, session.scheduledEndMs)
                    .find { it.phase.id == phase.id }?.endMs
            }
    }
    fun goToPhase(target: BlockPhase) {
        val block = sessionBlock ?: return
        val now = System.currentTimeMillis()
        // First manual step: keep the phases the plan already ran, at their planned starts.
        if (session.phaseStarts.isEmpty()) {
            phaseWindows(block, activeTasks, session.startedAtMs, session.scheduledEndMs)
                .takeWhile { it.phase.id != target.id }
                .forEach { blockSessionStore.startPhase(it.phase.id, minOf(it.startMs, now)) }
        }
        blockSessionStore.startPhase(target.id, now)
    }
    // Checklist groups: before, during (unphased, then each phase in order), after.
    val taskGroups = buildList {
        val byPlacement = activeTasks.groupBy { it.placement }
        byPlacement[BlockTaskPlacement.BEFORE]?.let { add(TaskGroup("Before block", it)) }
        val during = byPlacement[BlockTaskPlacement.DURING].orEmpty()
        val block = sessionBlock
        if (block == null || block.phases.isEmpty()) {
            if (during.isNotEmpty()) add(TaskGroup("During block", during))
        } else {
            during.filter { it.phaseOf(block) == null }.takeIf { it.isNotEmpty() }?.let { add(TaskGroup("During block", it)) }
            block.phases.forEach { phase ->
                val inPhase = during.filter { it.phaseOf(block)?.id == phase.id }
                if (inPhase.isNotEmpty()) add(TaskGroup(phase.name, inPhase, isCurrent = phase.id == currentPhase?.id))
            }
        }
        byPlacement[BlockTaskPlacement.AFTER]?.let { add(TaskGroup("After block", it)) }
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

    // Compute next occurrence (within 7 days). resolveForDate only resolves fixed (non-floating)
    // blocks, so a floating block's session falls back to a best-effort day-condition check —
    // otherwise this always came back null and floating blocks never got the completion-mode
    // situational-task planning flow that fixed blocks get.
    val nextOccurrenceDate = remember(session.blockId) {
        val block = namedBlockStore.loadBlock(session.blockId)
        val candidates = (1..7).map { today.plusDays(it.toLong()) }
        if (block?.isFloating == true) {
            candidates.firstOrNull { namedBlockStore.isFloatingBlockPossibleOn(block, it) }
        } else {
            candidates.firstOrNull { namedBlockStore.resolveForDate(it).any { (b, _) -> b.id == session.blockId } }
        }
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
                trainingAppWorkoutId(session.blockId)?.let { workoutId ->
                    IconButton(onClick = { openTrainingAppWorkout(context, workoutId) }) {
                        Icon(
                            Icons.Filled.OpenInNew,
                            contentDescription = "Open in Might",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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

        // Phase bar: which phase is on, how long it has left, and Next.
        if (!completionMode && sessionBlock != null && sessionBlock.phases.isNotEmpty() &&
            (currentPhase != null || nextPhase != null)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val phaseText = when {
                    currentPhase != null -> {
                        val left = currentPhaseEndMs?.let { ((it - nowForPhase) / 60_000L).toInt() }
                        currentPhase.name + when {
                            left == null -> ""
                            left > 0 -> " · ${left}m left"
                            else -> " · over time"
                        }
                    }
                    else -> "No phase running"
                }
                Text(
                    phaseText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                    modifier = Modifier.weight(1f)
                )
                if (nextPhase != null) {
                    TextButton(onClick = { goToPhase(nextPhase) }) {
                        Text(
                            if (currentPhase == null) "Start ${nextPhase.name}" else "Next: ${nextPhase.name}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

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
                taskGroups.forEach { group ->
                    val tasksForPlacement = group.tasks
                        .filter { !taskManager.completions.isSkipped(it.id) }
                        .sortedBy { it.sequence ?: Int.MAX_VALUE }
                    if (tasksForPlacement.isEmpty()) return@forEach
                    Text(
                        text = if (group.isCurrent) "${group.label} · now" else group.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (group.isCurrent) FontWeight.SemiBold else null,
                        color = accentColor.copy(alpha = if (group.isCurrent) 1f else 0.55f),
                        modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp)
                    )
                    tasksForPlacement.forEach { task ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { toggleTask(task.id) }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val checked = checkState[task.id] == true
                            RoundCheckbox(
                                checked = checked,
                                onClick = { toggleTask(task.id) },
                                onLongClick = if (!checked) { { backdateTarget = task.id } } else null
                            )
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
                            IconButton(
                                onClick = { skipBlockTask(task.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.SkipNext,
                                    contentDescription = "Skip today",
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                )
                            }
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

    backdateTarget?.let { taskId ->
        BackdateCompletionDialog(
            onDismiss = { backdateTarget = null },
            onConfirm = { whenMs ->
                checkState[taskId] = true
                taskManager.markDoneAt(taskId, whenMs)
                backdateTarget = null
                onRefresh()
            }
        )
    }
}

// ── Scheduled blocks dropdown (14-day fixed-schedule overview, editable) ──────

private val scheduleDateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private data class ResolvedDay(
    val enabled: Boolean,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int
)

private fun resolveDayForBlock(namedBlockStore: NamedBlockStore, block: NamedBlock, date: LocalDate): ResolvedDay {
    val override = namedBlockStore.getSchedule(block.id, date)
    val isRecurring = block.recurrenceRule?.occursOn(date) ?: (date.dayOfWeek.value in block.recurringDays)
    return ResolvedDay(
        enabled = override?.enabled ?: isRecurring,
        startHour = override?.startHour ?: block.defaultStartHour,
        startMinute = override?.startMinute ?: block.defaultStartMinute,
        endHour = override?.endHour?.takeIf { it != -1 } ?: block.defaultEndHour,
        endMinute = override?.endMinute ?: block.defaultEndMinute
    )
}

private fun writeDaySchedule(
    namedBlockStore: NamedBlockStore,
    block: NamedBlock,
    date: LocalDate,
    resolved: ResolvedDay,
    enabled: Boolean = resolved.enabled,
    startHour: Int = resolved.startHour,
    startMinute: Int = resolved.startMinute,
    endHour: Int = resolved.endHour,
    endMinute: Int = resolved.endMinute
) {
    namedBlockStore.setSchedule(
        NamedBlockSchedule(
            blockId = block.id,
            date = date.format(scheduleDateFmt),
            enabled = enabled,
            startHour = startHour,
            startMinute = startMinute,
            endHour = endHour,
            endMinute = endMinute
        )
    )
}

@Composable
private fun ScheduledBlocksDropdown(namedBlockStore: NamedBlockStore, refreshKey: Int) {
    var localRefreshKey by remember { mutableIntStateOf(0) }
    val fixedBlocks = remember(refreshKey) { namedBlockStore.loadAllBlocks().filter { !it.isFloating && it.enabled } }
    if (fixedBlocks.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val today = remember { LocalDate.now() }
    val next14 = remember { (0..13).map { today.plusDays(it.toLong()) } }
    val resolvedByBlock = remember(refreshKey, localRefreshKey, fixedBlocks) {
        fixedBlocks.associate { block ->
            block.id to next14.associateWith { date -> resolveDayForBlock(namedBlockStore, block, date) }
        }
    }
    var editingTarget by remember { mutableStateOf<Pair<NamedBlock, LocalDate>?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Scheduled blocks",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${fixedBlocks.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(Modifier.padding(bottom = 10.dp)) {
                Text(
                    text = "Tap a day to toggle it, the pencil to change its time. Also editable from each block's own settings in the Blocks tab.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
                )
                fixedBlocks.forEach { block ->
                    BlockScheduleSection(
                        block = block,
                        next14 = next14,
                        resolvedDays = resolvedByBlock[block.id] ?: emptyMap(),
                        onToggleDay = { date ->
                            val r = resolvedByBlock[block.id]?.get(date)
                            if (r != null) {
                                writeDaySchedule(namedBlockStore, block, date, r, enabled = !r.enabled)
                                localRefreshKey++
                            }
                        },
                        onEditDay = { date -> editingTarget = block to date }
                    )
                }
            }
        }
    }

    val target = editingTarget
    if (target != null) {
        val (targetBlock, targetDate) = target
        val r = resolvedByBlock[targetBlock.id]?.get(targetDate)
            ?: ResolvedDay(false, targetBlock.defaultStartHour, targetBlock.defaultStartMinute, targetBlock.defaultEndHour, targetBlock.defaultEndMinute)
        DayScheduleEditDialog(
            resolved = r,
            onDismiss = { editingTarget = null },
            onConfirm = { startH, startM, endH, endM ->
                writeDaySchedule(
                    namedBlockStore, targetBlock, targetDate, r,
                    enabled = true, startHour = startH, startMinute = startM,
                    endHour = endH, endMinute = endM
                )
                localRefreshKey++
                editingTarget = null
            }
        )
    }
}

// A day's end time can be a fixed override set via a timeline resize-drag even when the block
// itself defaults to an estimated (no fixed end) duration, so this dialog exposes start, end
// (when one is set), and a way to clear a stray fixed end back to the block's usual behavior —
// none of which the timeline drag itself offers a way to undo precisely.
@Composable
private fun DayScheduleEditDialog(
    resolved: ResolvedDay,
    onDismiss: () -> Unit,
    onConfirm: (startHour: Int, startMinute: Int, endHour: Int, endMinute: Int) -> Unit
) {
    var startH by remember { mutableIntStateOf(resolved.startHour) }
    var startM by remember { mutableIntStateOf(resolved.startMinute) }
    var endH by remember { mutableIntStateOf(resolved.endHour) }
    var endM by remember { mutableIntStateOf(resolved.endMinute) }
    var editingStart by remember { mutableStateOf(false) }
    var editingEnd by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit schedule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Start", modifier = Modifier.weight(1f))
                    TextButton(onClick = { editingStart = true }) {
                        Text("%02d:%02d".format(startH, startM))
                    }
                }
                if (endH != -1) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("End", modifier = Modifier.weight(1f))
                        TextButton(onClick = { editingEnd = true }) {
                            Text("%02d:%02d".format(endH, endM))
                        }
                        TextButton(onClick = { endH = -1; endM = 0 }) { Text("Clear") }
                    }
                    Text(
                        "Clearing removes this day's fixed end time and falls back to the block's usual length.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(startH, startM, endH, endM) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (editingStart) {
        TimePickerDialog(
            initialHour = startH,
            initialMinute = startM,
            onDismiss = { editingStart = false },
            onConfirm = { h, m -> startH = h; startM = m; editingStart = false }
        )
    }
    if (editingEnd) {
        TimePickerDialog(
            initialHour = endH.takeIf { it != -1 } ?: 12,
            initialMinute = endM,
            onDismiss = { editingEnd = false },
            onConfirm = { h, m -> endH = h; endM = m; editingEnd = false }
        )
    }
}

@Composable
private fun BlockScheduleSection(
    block: NamedBlock,
    next14: List<LocalDate>,
    resolvedDays: Map<LocalDate, ResolvedDay>,
    onToggleDay: (LocalDate) -> Unit,
    onEditDay: (LocalDate) -> Unit
) {
    val accent = block.colorArgb?.toOpaqueColor() ?: MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                text = block.name,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = accent
            )
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(next14) { date ->
                val r = resolvedDays[date]
                    ?: ResolvedDay(false, block.defaultStartHour, block.defaultStartMinute, block.defaultEndHour, block.defaultEndMinute)
                MiniDayChip(
                    date = date,
                    resolved = r,
                    accent = accent,
                    onToggle = { onToggleDay(date) },
                    onEditTime = { onEditDay(date) }
                )
            }
        }
    }
}

@Composable
private fun MiniDayChip(
    date: LocalDate,
    resolved: ResolvedDay,
    accent: Color,
    onToggle: () -> Unit,
    onEditTime: () -> Unit
) {
    val scheduled = resolved.enabled
    val dayName = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    val timeLabel = if (scheduled) {
        val start = "%02d:%02d".format(resolved.startHour, resolved.startMinute)
        if (resolved.endHour >= 0) "$start–%02d:%02d".format(resolved.endHour, resolved.endMinute) else start
    } else "Off"

    Column(
        Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (scheduled) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = if (scheduled) accent.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = dayName,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = if (scheduled) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "${date.dayOfMonth}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (scheduled) 0.85f else 0.4f)
        )
        Text(
            text = timeLabel,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            color = if (scheduled) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            maxLines = 1
        )
        // Sized well above the icon's visual footprint so the edit tap target doesn't
        // collide with the day-toggle tap area that covers the rest of this chip.
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onEditTime),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit schedule",
                tint = if (scheduled) accent.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

// ── Round checkbox ────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoundCheckbox(
    checked: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 22.dp
) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (onClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                else Modifier
            )
            .background(if (checked) primary else Color.Transparent)
            .border(1.5.dp, if (checked) primary else outline.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(size * 0.6f)
            )
        }
    }
}

/** Long-press-on-checkbox dialog: "actually finished a bit earlier, not just now". Hour/minute
 *  only (no date) — if the picked time is later than now, it can only mean yesterday, since a
 *  completion log is never in the future. */
@Composable
private fun BackdateCompletionDialog(onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val now = remember { Calendar.getInstance() }
    TimePickerDialog(
        initialHour = now.get(Calendar.HOUR_OF_DAY),
        initialMinute = now.get(Calendar.MINUTE),
        onDismiss = onDismiss,
        onConfirm = { h, m ->
            val picked = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, h)
                set(Calendar.MINUTE, m)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (picked.timeInMillis > System.currentTimeMillis()) picked.add(Calendar.DAY_OF_MONTH, -1)
            onConfirm(picked.timeInMillis)
        }
    )
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
            (context.applicationContext as? WaypointApplication)?.cycleTracker?.recordActive()
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
            val isMonitoring = sleepState == SleepModeState.MONITORING
            Surface(
                shape = RoundedCornerShape(50),
                color = when {
                    badgeLogged  -> MaterialTheme.colorScheme.surface
                    isMonitoring -> MaterialTheme.colorScheme.errorContainer
                    else         -> MaterialTheme.colorScheme.secondaryContainer
                }
            ) {
                Text(
                    text = badgeText,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        badgeLogged  -> MaterialTheme.colorScheme.onSurfaceVariant
                        isMonitoring -> MaterialTheme.colorScheme.onErrorContainer
                        else         -> MaterialTheme.colorScheme.onSecondaryContainer
                    }
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

/** What a task's countdown says, and how urgent it looks. */
internal enum class CountdownState { UPCOMING, NOW, OVERDUE }
internal data class TodoCountdown(val label: String, val state: CountdownState)

/** "1h 5m", "25m", or "<1m". */
internal fun formatSpan(ms: Long): String {
    val minutes = (ms / 60_000L).toInt()
    return when {
        minutes < 1 -> "<1m"
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}

/** "in 25m" before [startMs], "now · 12m left" during, "overdue 15m" after [endMs]. */
internal fun todoCountdown(nowMs: Long, startMs: Long, endMs: Long): TodoCountdown = when {
    nowMs < startMs -> TodoCountdown("in ${formatSpan(startMs - nowMs)}", CountdownState.UPCOMING)
    nowMs < endMs -> TodoCountdown("now · ${formatSpan(endMs - nowMs)} left", CountdownState.NOW)
    else -> TodoCountdown("overdue ${formatSpan(nowMs - endMs)}", CountdownState.OVERDUE)
}

/** A to-do list section heading; with [onClick] it folds its section open and shut. */
@Composable
private fun TodoSectionHeader(title: String, expanded: Boolean? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (expanded != null) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide" else "Show",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * One to-do: a tick circle, the task and when it's planned (and its block, for a block's task),
 * a countdown to it, and a ⋮ menu with the rest (timer, earlier completion, edit, skip, delete).
 * Long-pressing the circle logs it done earlier, as before.
 */
@Composable
private fun TodoRow(
    se: ScheduledEvent,
    blockName: String?,
    blockColor: Color?,
    done: Boolean,
    runningSinceMs: Long?,
    nowMs: Long,
    chained: Boolean,
    measured: Boolean,
    onToggle: () -> Unit,
    onStart: (() -> Unit)?,
    onStop: () -> Unit,
    onEdit: (() -> Unit)?,
    onSkip: () -> Unit,
    onDelete: (() -> Unit)?,
    onMarkDoneAt: (Long) -> Unit,
    onLogPastExecution: (startMs: Long, endMs: Long) -> Unit
) {
    val running = runningSinceMs != null
    var menuOpen by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    if (showDeleteDialog && onDelete != null) {
        TaskDeleteDialog(taskTitle = se.event.title, onDelete = onDelete, onDismiss = { showDeleteDialog = false })
    }
    // Logging a completion that happened earlier: a time, or for a measured task a start then
    // an end (the same picker asked twice).
    var showBackdateStart by remember { mutableStateOf(false) }
    var pendingBackdateStart by remember { mutableStateOf<Long?>(null) }
    if (showBackdateStart) {
        BackdateCompletionDialog(
            onDismiss = { showBackdateStart = false },
            onConfirm = { startMs ->
                showBackdateStart = false
                if (measured) pendingBackdateStart = startMs else onMarkDoneAt(startMs)
            }
        )
    }
    pendingBackdateStart?.let { startMs ->
        BackdateCompletionDialog(
            onDismiss = { pendingBackdateStart = null },
            onConfirm = { endMs ->
                pendingBackdateStart = null
                onLogPastExecution(startMs, maxOf(endMs, startMs + 60_000L))
            }
        )
    }

    val primary = MaterialTheme.colorScheme.primary
    val accent = se.event.colorArgb?.toOpaqueColor() ?: blockColor ?: primary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (running) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (done) 0.25f else 0.5f)
            )
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundCheckbox(
            checked = done,
            onClick = if (!running) onToggle else null,
            onLongClick = if (!running && !done) { { showBackdateStart = true } } else null,
            size = 26.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = se.event.title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None
                ),
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatShiftTime(se.startMillis)} – ${formatShiftTime(se.endMillis)} · ${formatSpan(se.endMillis - se.startMillis)}" +
                        if (chained) " · chained" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (blockName != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        blockName,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(accent.copy(alpha = 0.15f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
        }
        // Countdown (or the running timer, which stops when tapped); nothing once it's done.
        if (!done) {
            Spacer(Modifier.width(8.dp))
            if (runningSinceMs != null) {
                val secs = ((nowMs - runningSinceMs) / 1000L).coerceAtLeast(0)
                CountdownPill("▶ %d:%02d".format(secs / 60, secs % 60), primary, onClick = onStop)
            } else {
                val c = todoCountdown(nowMs, se.startMillis, se.endMillis)
                CountdownPill(
                    c.label,
                    when (c.state) {
                        CountdownState.UPCOMING -> MaterialTheme.colorScheme.onSurfaceVariant
                        CountdownState.NOW -> primary
                        CountdownState.OVERDUE -> MaterialTheme.colorScheme.error
                    }
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More for ${se.event.title}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (!done) {
                    if (running) {
                        DropdownMenuItem(text = { Text("Stop timer") }, onClick = { menuOpen = false; onStop() })
                    } else if (onStart != null) {
                        DropdownMenuItem(text = { Text("Start timer") }, onClick = { menuOpen = false; onStart() })
                    }
                    if (!running) {
                        DropdownMenuItem(
                            text = { Text("Done earlier…") },
                            onClick = { menuOpen = false; showBackdateStart = true }
                        )
                    }
                }
                if (onEdit != null) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                }
                if (!done) {
                    DropdownMenuItem(text = { Text("Skip today") }, onClick = { menuOpen = false; onSkip() })
                }
                if (onDelete != null) {
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; showDeleteDialog = true }
                    )
                }
            }
        }
    }
}

@Composable
private fun CountdownPill(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
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
        IconButton(onClick = onSkip, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = "Skip today",
                modifier = Modifier.size(18.dp),
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

@Composable
private fun SkippedTaskRow(title: String, onUnskip: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Default.SkipNext,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onUnskip) { Text("Un-skip") }
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
            // Triggered again means to do again, even if it was done or skipped earlier this wake.
            taskManager.unskipTask(target.id)
            if (taskManager.isDone(target.id)) taskManager.unmarkDone(target.id)
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
