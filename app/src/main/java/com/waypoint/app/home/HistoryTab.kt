package com.waypoint.app.home

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.waypoint.app.cycle.CycleTracker
import com.waypoint.app.cycle.CyclesTab
import com.waypoint.app.planner.BlockSessionLog
import com.waypoint.app.planner.BlockSessionLogStore
import com.waypoint.app.planner.BlockTaskMeasurement
import com.waypoint.app.planner.NamedBlockStore
import com.waypoint.app.planner.SleepLogEntry
import com.waypoint.app.planner.SleepLogStore
import com.waypoint.app.planner.TaskExecution
import com.waypoint.app.script.TaskManagerScript
import com.waypoint.app.ui.components.TimePickerChip
import java.util.Calendar

private enum class HistoryCategory { TASKS, SLEEP, CYCLES, BLOCKS }

@Composable
fun HistoryTab(
    cycleTracker: CycleTracker,
    taskManager: TaskManagerScript,
    blockSessionLogStore: BlockSessionLogStore? = null,
    namedBlockStore: NamedBlockStore? = null
) {
    val context = LocalContext.current
    var category by remember { mutableStateOf(HistoryCategory.CYCLES) }
    var refreshKey by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HistoryCategory.entries.forEach { cat ->
                FilterChip(
                    selected = category == cat,
                    onClick = { category = cat; refreshKey++ },
                    label = {
                        Text(
                            text = when (cat) {
                                HistoryCategory.TASKS  -> "Tasks"
                                HistoryCategory.SLEEP  -> "Sleep"
                                HistoryCategory.CYCLES -> "Cycles"
                                HistoryCategory.BLOCKS -> "Blocks"
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when (category) {
            HistoryCategory.TASKS  -> TaskHistoryContent(taskManager = taskManager, refreshKey = refreshKey)
            HistoryCategory.SLEEP  -> SleepHistoryContent(context = context, refreshKey = refreshKey)
            HistoryCategory.CYCLES -> CyclesTab(cycleTracker = cycleTracker)
            HistoryCategory.BLOCKS -> BlockHistoryContent(
                logStore = blockSessionLogStore,
                namedBlockStore = namedBlockStore,
                refreshKey = refreshKey
            )
        }
    }
}

// ── Task history ──────────────────────────────────────────────────────────────

private data class TaskSummary(
    val taskId: String,
    val title: String,
    val executions: List<TaskExecution>
) {
    val count: Int get() = executions.size
    val avgMinutes: Int? get() {
        val measured = executions.mapNotNull { it.measuredMinutes }
        return if (measured.isEmpty()) null else measured.average().toInt()
    }
}

@Composable
private fun TaskHistoryContent(taskManager: TaskManagerScript, refreshKey: Int) {
    var localKey by remember { mutableIntStateOf(0) }
    val combinedKey = refreshKey + localKey
    var drillTarget by remember { mutableStateOf<TaskSummary?>(null) }

    val summaries = remember(combinedKey) {
        val allTasks = taskManager.getAllTasks().associateBy { it.id }
        taskManager.executions.loadAll()
            .filter { it.endMillis != null }
            .groupBy { it.taskId }
            .map { (taskId, execs) ->
                TaskSummary(
                    taskId = taskId,
                    title = allTasks[taskId]?.title ?: "Deleted task",
                    executions = execs.sortedByDescending { it.startMillis }
                )
            }
            .sortedByDescending { it.executions.first().startMillis }
    }

    if (summaries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No tasks completed yet.\n\nTap the play button on a task to start the timer.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp)
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        items(summaries, key = { it.taskId }) { summary ->
            TaskSummaryRow(summary = summary, onClick = { drillTarget = summary })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        }
    }

    drillTarget?.let { summary ->
        TaskDrillInSheet(
            title = summary.title,
            executions = summary.executions,
            onUpdate = { updated ->
                taskManager.executions.update(updated)
                localKey++
                // rebuild the summary from the updated execution list
                val newExecs = summary.executions.map { if (it.startMillis == updated.startMillis) updated else it }
                drillTarget = summary.copy(executions = newExecs)
            },
            onDelete = { exec ->
                taskManager.executions.clear(exec.taskId, exec.startMillis)
                localKey++
                val remaining = summary.executions.filter { it.startMillis != exec.startMillis }
                drillTarget = if (remaining.isEmpty()) null else summary.copy(executions = remaining)
            },
            onDismiss = { drillTarget = null; localKey++ }
        )
    }
}

@Composable
private fun TaskSummaryRow(summary: TaskSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${summary.count} ${if (summary.count == 1) "run" else "runs"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        summary.avgMinutes?.let { avg ->
            Text(
                text = "avg ${avg}m",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun TaskDrillInSheet(
    title: String,
    executions: List<TaskExecution>,
    onUpdate: (TaskExecution) -> Unit,
    onDelete: (TaskExecution) -> Unit,
    onDismiss: () -> Unit
) {
    var editTarget by remember { mutableStateOf<TaskExecution?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val avgMins = executions.mapNotNull { it.measuredMinutes }
                            .takeIf { it.isNotEmpty() }?.average()?.toInt()
                        Text(
                            text = buildString {
                                append("${executions.size} ${if (executions.size == 1) "run" else "runs"}")
                                if (avgMins != null) append(" · avg ${avgMins}m")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(executions, key = { it.startMillis }) { exec ->
                        TaskExecutionRow(
                            execution = exec,
                            onEdit = { editTarget = exec },
                            onDelete = { onDelete(exec) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    }
                }
            }
        }
    }

    editTarget?.let { exec ->
        EditTaskExecutionSheet(
            execution = exec,
            onDismiss = { editTarget = null },
            onSave = { newStart, newEnd ->
                onUpdate(exec.copy(startMillis = newStart, endMillis = newEnd))
                editTarget = null
            }
        )
    }
}

@Composable
private fun TaskExecutionRow(
    execution: TaskExecution,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = fmtDate(execution.startMillis),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            val endStr = execution.endMillis?.let { fmtMs(it) } ?: "--:--"
            Text(
                text = "${fmtMs(execution.startMillis)} – $endStr",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        execution.measuredMinutes?.let { m ->
            Text(
                text = "${m}m",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Delete",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun EditTaskExecutionSheet(
    execution: TaskExecution,
    onDismiss: () -> Unit,
    onSave: (startMillis: Long, endMillis: Long) -> Unit
) {
    val startCal = remember(execution) { Calendar.getInstance().apply { timeInMillis = execution.startMillis } }
    val endCal   = remember(execution) { Calendar.getInstance().apply { timeInMillis = execution.endMillis ?: execution.startMillis } }

    var startTime by remember { mutableStateOf("%02d:%02d".format(startCal.get(Calendar.HOUR_OF_DAY), startCal.get(Calendar.MINUTE))) }
    var endTime   by remember { mutableStateOf("%02d:%02d".format(endCal.get(Calendar.HOUR_OF_DAY), endCal.get(Calendar.MINUTE))) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Edit Entry", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = fmtDate(execution.startMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Start", style = MaterialTheme.typography.bodyMedium)
                    TimePickerChip(value = startTime, onValueChange = { startTime = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("End", style = MaterialTheme.typography.bodyMedium)
                    TimePickerChip(value = endTime, onValueChange = { endTime = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = {
                        val newStart = setTimeOnMs(execution.startMillis, startTime)
                        val newEnd   = setTimeOnMs(execution.endMillis ?: execution.startMillis, endTime)
                        onSave(newStart, newEnd)
                    }) { Text("Save") }
                }
            }
        }
    }
}

// ── Block history ─────────────────────────────────────────────────────────────

private data class BlockGroup(
    val blockId: String,
    val blockName: String,
    val colorArgb: Int?,
    val sessions: List<BlockSessionLog>
) {
    val lastDate: String get() = sessions.firstOrNull()?.date ?: ""
}

private data class MeasurementRecord(
    val measurement: BlockTaskMeasurement,
    val sessionDate: String,
    val blockId: String,
    val sessionStartedAtMs: Long
)

@Composable
private fun BlockHistoryContent(
    logStore: BlockSessionLogStore?,
    namedBlockStore: NamedBlockStore?,
    refreshKey: Int
) {
    if (logStore == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Block session history unavailable",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp)
            )
        }
        return
    }

    var localKey by remember { mutableIntStateOf(0) }
    val combinedKey = refreshKey + localKey
    var expandedBlockId by remember { mutableStateOf<String?>(null) }
    var measurementDrillTarget by remember { mutableStateOf<Pair<String, List<MeasurementRecord>>?>(null) }
    var editSession by remember { mutableStateOf<BlockSessionLog?>(null) }

    val groups = remember(combinedKey) {
        logStore.loadAll()
            .groupBy { it.blockId }
            .map { (blockId, sessions) ->
                BlockGroup(
                    blockId = blockId,
                    blockName = sessions.first().blockName,
                    colorArgb = sessions.first().colorArgb,
                    sessions = sessions.sortedByDescending { it.startedAtMs }
                )
            }
            .sortedByDescending { it.sessions.first().startedAtMs }
    }

    // Pre-compute task measurement stats per block (can't use remember inside LazyListScope)
    val allTaskStats = remember(combinedKey) {
        groups.associate { group ->
            group.blockId to group.sessions
                .flatMap { s -> s.taskMeasurements.map { m -> MeasurementRecord(m, s.date, s.blockId, s.startedAtMs) } }
                .groupBy { it.measurement.taskId }
                .mapValues { (_, records) ->
                    val avg = records.map { it.measurement.measuredMinutes }.average().toInt()
                    Triple(records.size, avg, records.sortedByDescending { it.measurement.startMs })
                }
        }
    }

    if (groups.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No block sessions yet.\n\nCompleted sessions appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp)
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        groups.forEach { group ->
            item(key = group.blockId) {
                BlockGroupHeader(
                    group = group,
                    expanded = expandedBlockId == group.blockId,
                    onToggle = {
                        expandedBlockId = if (expandedBlockId == group.blockId) null else group.blockId
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            }

            if (expandedBlockId == group.blockId) {
                // Sessions sub-section
                item(key = "${group.blockId}_sessions_header") {
                    Text(
                        text = "Sessions",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp)
                    )
                }
                items(group.sessions, key = { "${group.blockId}_s_${it.startedAtMs}" }) { session ->
                    BlockSessionRow(
                        session = session,
                        onEdit = { editSession = session },
                        onDelete = {
                            logStore.deleteEntry(session.blockId, session.startedAtMs)
                            localKey++
                        }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }

                // Block tasks sub-section
                val taskStats = allTaskStats[group.blockId] ?: emptyMap()

                if (taskStats.isNotEmpty()) {
                    item(key = "${group.blockId}_tasks_header") {
                        Text(
                            text = "Block tasks",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp)
                        )
                    }
                    taskStats.entries.toList().sortedByDescending { it.value.first }
                        .forEach { (taskId, statTriple) ->
                            val (count, avg, records) = statTriple
                            val taskTitle = namedBlockStore?.loadTask(taskId)?.title ?: "Deleted task"
                            item(key = "${group.blockId}_t_$taskId") {
                                BlockTaskStatsRow(
                                    title = taskTitle,
                                    count = count,
                                    avgMinutes = avg,
                                    onClick = { measurementDrillTarget = taskTitle to records }
                                )
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                    modifier = Modifier.padding(start = 16.dp)
                                )
                            }
                        }
                }

                item(key = "${group.blockId}_spacer") {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }

    editSession?.let { session ->
        EditBlockSessionSheet(
            session = session,
            onDismiss = { editSession = null },
            onSave = { newStart, newEnd ->
                logStore.updateEntry(session.copy(startedAtMs = newStart, endedAtMs = newEnd))
                editSession = null
                localKey++
            }
        )
    }

    measurementDrillTarget?.let { (taskTitle, records) ->
        MeasurementDrillInSheet(
            title = taskTitle,
            records = records,
            onDelete = { rec ->
                val session = logStore.loadAll().find {
                    it.blockId == rec.blockId && it.startedAtMs == rec.sessionStartedAtMs
                }
                if (session != null) {
                    val updated = session.copy(
                        taskMeasurements = session.taskMeasurements.filter {
                            !(it.taskId == rec.measurement.taskId && it.startMs == rec.measurement.startMs)
                        }
                    )
                    logStore.updateEntry(updated)
                    localKey++
                    val remaining = records.filter { it.measurement.startMs != rec.measurement.startMs }
                    measurementDrillTarget = if (remaining.isEmpty()) null else taskTitle to remaining
                }
            },
            onUpdate = { rec, newStart, newEnd ->
                val session = logStore.loadAll().find {
                    it.blockId == rec.blockId && it.startedAtMs == rec.sessionStartedAtMs
                }
                if (session != null) {
                    val newMins = ((newEnd - newStart) / 60_000L).coerceAtLeast(1).toInt()
                    val updatedM = rec.measurement.copy(startMs = newStart, endMs = newEnd, measuredMinutes = newMins)
                    val updated = session.copy(
                        taskMeasurements = session.taskMeasurements.map {
                            if (it.taskId == rec.measurement.taskId && it.startMs == rec.measurement.startMs) updatedM else it
                        }
                    )
                    logStore.updateEntry(updated)
                    localKey++
                    val newRec = rec.copy(measurement = updatedM)
                    val newRecords = records.map { if (it.measurement.startMs == rec.measurement.startMs) newRec else it }
                    measurementDrillTarget = taskTitle to newRecords
                }
            },
            onDismiss = { measurementDrillTarget = null; localKey++ }
        )
    }
}

@Composable
private fun BlockGroupHeader(group: BlockGroup, expanded: Boolean, onToggle: () -> Unit) {
    val accentColor = group.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(36.dp)
                .background(accentColor, RoundedCornerShape(2.dp))
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.blockName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = buildString {
                    append("${group.sessions.size} ${if (group.sessions.size == 1) "session" else "sessions"}")
                    if (group.lastDate.isNotEmpty()) append(" · last ${group.lastDate}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse" else "Expand",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun BlockSessionRow(
    session: BlockSessionLog,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val durationMins = ((session.endedAtMs - session.startedAtMs) / 60_000L).toInt()
    val h = durationMins / 60
    val m = durationMins % 60
    val durText = when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                text = session.date,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${fmtMs(session.startedAtMs)} – ${fmtMs(session.endedAtMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = durText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (session.tasksTotal > 0) {
                Text(
                    text = "${session.tasksCompleted}/${session.tasksTotal}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Delete",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun EditBlockSessionSheet(
    session: BlockSessionLog,
    onDismiss: () -> Unit,
    onSave: (startMs: Long, endMs: Long) -> Unit
) {
    val startCal = remember(session) { Calendar.getInstance().apply { timeInMillis = session.startedAtMs } }
    val endCal   = remember(session) { Calendar.getInstance().apply { timeInMillis = session.endedAtMs } }

    var startTime by remember { mutableStateOf("%02d:%02d".format(startCal.get(Calendar.HOUR_OF_DAY), startCal.get(Calendar.MINUTE))) }
    var endTime   by remember { mutableStateOf("%02d:%02d".format(endCal.get(Calendar.HOUR_OF_DAY), endCal.get(Calendar.MINUTE))) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Edit Session", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${session.blockName} · ${session.date}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Start", style = MaterialTheme.typography.bodyMedium)
                    TimePickerChip(value = startTime, onValueChange = { startTime = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("End", style = MaterialTheme.typography.bodyMedium)
                    TimePickerChip(value = endTime, onValueChange = { endTime = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = {
                        val newStart = setTimeOnMs(session.startedAtMs, startTime)
                        val newEnd   = setTimeOnMs(session.endedAtMs, endTime)
                        onSave(newStart, newEnd)
                    }) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun BlockTaskStatsRow(title: String, count: Int, avgMinutes: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$count ${if (count == 1) "entry" else "entries"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            )
        }
        Text(
            text = "avg ${avgMinutes}m",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp)
        )
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun MeasurementDrillInSheet(
    title: String,
    records: List<MeasurementRecord>,
    onDelete: (MeasurementRecord) -> Unit,
    onUpdate: (MeasurementRecord, newStart: Long, newEnd: Long) -> Unit,
    onDismiss: () -> Unit
) {
    var editTarget by remember { mutableStateOf<MeasurementRecord?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = title, style = MaterialTheme.typography.titleMedium)
                        val avg = records.map { it.measurement.measuredMinutes }.average().toInt()
                        Text(
                            text = "${records.size} ${if (records.size == 1) "entry" else "entries"} · avg ${avg}m",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(records, key = { "${it.sessionStartedAtMs}_${it.measurement.startMs}" }) { rec ->
                        MeasurementRow(
                            record = rec,
                            onEdit = { editTarget = rec },
                            onDelete = { onDelete(rec) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    }
                }
            }
        }
    }

    editTarget?.let { rec ->
        EditMeasurementSheet(
            record = rec,
            onDismiss = { editTarget = null },
            onSave = { newStart, newEnd ->
                onUpdate(rec, newStart, newEnd)
                editTarget = null
            }
        )
    }
}

@Composable
private fun MeasurementRow(
    record: MeasurementRecord,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = record.sessionDate,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${fmtMs(record.measurement.startMs)} – ${fmtMs(record.measurement.endMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        Text(
            text = "${record.measurement.measuredMinutes}m",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Delete",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun EditMeasurementSheet(
    record: MeasurementRecord,
    onDismiss: () -> Unit,
    onSave: (startMs: Long, endMs: Long) -> Unit
) {
    val startCal = remember(record) { Calendar.getInstance().apply { timeInMillis = record.measurement.startMs } }
    val endCal   = remember(record) { Calendar.getInstance().apply { timeInMillis = record.measurement.endMs } }

    var startTime by remember { mutableStateOf("%02d:%02d".format(startCal.get(Calendar.HOUR_OF_DAY), startCal.get(Calendar.MINUTE))) }
    var endTime   by remember { mutableStateOf("%02d:%02d".format(endCal.get(Calendar.HOUR_OF_DAY), endCal.get(Calendar.MINUTE))) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Edit Entry", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = record.sessionDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Start", style = MaterialTheme.typography.bodyMedium)
                    TimePickerChip(value = startTime, onValueChange = { startTime = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("End", style = MaterialTheme.typography.bodyMedium)
                    TimePickerChip(value = endTime, onValueChange = { endTime = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = {
                        val newStart = setTimeOnMs(record.measurement.startMs, startTime)
                        val newEnd   = setTimeOnMs(record.measurement.endMs, endTime)
                        onSave(newStart, newEnd)
                    }) { Text("Save") }
                }
            }
        }
    }
}

// ── Sleep history ─────────────────────────────────────────────────────────────

@Composable
private fun SleepHistoryContent(context: Context, refreshKey: Int) {
    val logStore = remember { SleepLogStore(context) }
    var localKey by remember { mutableIntStateOf(0) }
    val combinedKey = refreshKey + localKey
    val entries = remember(combinedKey) { logStore.loadRecent(30) }
    var editTarget by remember { mutableStateOf<SleepLogEntry?>(null) }

    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No sleep logged yet.\n\nSleep entries appear here once you log a session.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp)
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        items(entries, key = { it.dateIso }) { entry ->
            SleepLogRow(
                entry = entry,
                onEdit = { editTarget = entry },
                onDelete = {
                    logStore.deleteEntry(entry.dateIso)
                    localKey++
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        }
    }

    editTarget?.let { entry ->
        EditSleepEntrySheet(
            entry = entry,
            onDismiss = { editTarget = null },
            onSave = { newBed, newWake ->
                logStore.saveEntry(entry.copy(bedMillis = newBed, wakeMillis = newWake))
                editTarget = null
                localKey++
            }
        )
    }
}

@Composable
private fun SleepLogRow(entry: SleepLogEntry, onEdit: () -> Unit, onDelete: () -> Unit) {
    val durationMins = ((entry.wakeMillis - entry.bedMillis) / 60_000L).toInt()
    val h = durationMins / 60
    val m = durationMins % 60
    val durText = if (m == 0) "${h}h" else "${h}h ${m}m"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(text = entry.dateIso, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${fmtMs(entry.bedMillis)} – ${fmtMs(entry.wakeMillis)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        Text(text = durText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Edit, contentDescription = "Edit entry", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Delete entry", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun EditSleepEntrySheet(
    entry: SleepLogEntry,
    onDismiss: () -> Unit,
    onSave: (bedMillis: Long, wakeMillis: Long) -> Unit
) {
    val bedCal  = remember(entry) { Calendar.getInstance().apply { timeInMillis = entry.bedMillis } }
    val wakeCal = remember(entry) { Calendar.getInstance().apply { timeInMillis = entry.wakeMillis } }

    var bedTime  by remember { mutableStateOf("%02d:%02d".format(bedCal.get(Calendar.HOUR_OF_DAY), bedCal.get(Calendar.MINUTE))) }
    var wakeTime by remember { mutableStateOf("%02d:%02d".format(wakeCal.get(Calendar.HOUR_OF_DAY), wakeCal.get(Calendar.MINUTE))) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, modifier = Modifier.padding(horizontal = 32.dp)) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Edit Sleep Entry", style = MaterialTheme.typography.titleMedium)
                Text(text = entry.dateIso, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Bed", style = MaterialTheme.typography.bodyMedium)
                    TimePickerChip(value = bedTime, onValueChange = { bedTime = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Wake", style = MaterialTheme.typography.bodyMedium)
                    TimePickerChip(value = wakeTime, onValueChange = { wakeTime = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = {
                        onSave(setTimeOnMs(entry.bedMillis, bedTime), setTimeOnMs(entry.wakeMillis, wakeTime))
                    }) { Text("Save") }
                }
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun fmtMs(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

private fun fmtDate(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

private fun setTimeOnMs(originalMs: Long, hhMm: String): Long {
    val parts = hhMm.split(":").mapNotNull { it.toIntOrNull() }
    if (parts.size < 2) return originalMs
    return Calendar.getInstance().apply {
        timeInMillis = originalMs
        set(Calendar.HOUR_OF_DAY, parts[0])
        set(Calendar.MINUTE, parts[1])
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
