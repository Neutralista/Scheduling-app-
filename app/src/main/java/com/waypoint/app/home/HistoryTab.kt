package com.waypoint.app.home

import android.content.Context
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.waypoint.app.cycle.Cycle
import com.waypoint.app.cycle.CycleLogSheet
import com.waypoint.app.cycle.CycleTracker
import com.waypoint.app.planner.BlockSessionLog
import com.waypoint.app.planner.BlockSessionLogStore
import com.waypoint.app.planner.BlockTaskMeasurement
import com.waypoint.app.planner.NamedBlockStore
import com.waypoint.app.planner.SleepLogEntry
import com.waypoint.app.planner.SleepLogStore
import com.waypoint.app.planner.TaskExecution
import com.waypoint.app.script.TaskManagerScript
import com.waypoint.app.ui.components.TimePickerChip
import kotlinx.coroutines.delay
import java.util.Calendar

// ── Data models ───────────────────────────────────────────────────────────────

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

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

// ── Main composable ───────────────────────────────────────────────────────────

@Composable
fun HistoryTab(
    cycleTracker: CycleTracker,
    taskManager: TaskManagerScript,
    blockSessionLogStore: BlockSessionLogStore? = null,
    namedBlockStore: NamedBlockStore? = null
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var localKey by remember { mutableIntStateOf(0) }
    val combinedKey = refreshKey + localKey

    var expandedIds by remember { mutableStateOf(setOf<String>()) }

    // ── Data ──────────────────────────────────────────────────────────────────
    val taskSummaries = remember(combinedKey) {
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

    val blockGroups = remember(combinedKey) {
        blockSessionLogStore?.loadAll()
            ?.groupBy { it.blockId }
            ?.map { (blockId, sessions) ->
                BlockGroup(
                    blockId = blockId,
                    blockName = sessions.first().blockName,
                    colorArgb = sessions.first().colorArgb,
                    sessions = sessions.sortedByDescending { it.startedAtMs }
                )
            }
            ?.sortedByDescending { it.sessions.first().startedAtMs }
            ?: emptyList()
    }

    val allTaskStats = remember(combinedKey) {
        blockGroups.associate { group ->
            group.blockId to group.sessions
                .flatMap { s -> s.taskMeasurements.map { m -> MeasurementRecord(m, s.date, s.blockId, s.startedAtMs) } }
                .groupBy { it.measurement.taskId }
                .mapValues { (_, records) ->
                    val avg = records.map { it.measurement.measuredMinutes }.average().toInt()
                    Triple(records.size, avg, records.sortedByDescending { it.measurement.startMs })
                }
        }
    }

    val sleepLogStore = remember { SleepLogStore(context) }
    val sleepEntries = remember(combinedKey) { sleepLogStore.loadRecent(60) }
    val sleepAvgMins = remember(sleepEntries) {
        if (sleepEntries.isEmpty()) null
        else sleepEntries.map { ((it.wakeMillis - it.bedMillis) / 60_000L).toInt() }.average().toInt()
    }

    val allCycles = remember(combinedKey) { cycleTracker.store.loadAll() }
    val currentCycle = remember(allCycles) { allCycles.firstOrNull { it.isOpen } }
    val historyCycles = remember(allCycles) { allCycles.filter { !it.isOpen } }

    // Tick every minute for live cycle duration
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000L); tick++ } }

    // ── Edit / drill state ────────────────────────────────────────────────────
    var drillTaskTarget by remember { mutableStateOf<TaskSummary?>(null) }
    var measurementDrillTarget by remember { mutableStateOf<Pair<String, List<MeasurementRecord>>?>(null) }
    var editSession by remember { mutableStateOf<BlockSessionLog?>(null) }
    var editSleepEntry by remember { mutableStateOf<SleepLogEntry?>(null) }
    var editCycle by remember { mutableStateOf<Cycle?>(null) }

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary

    // ── Unified list ──────────────────────────────────────────────────────────
    if (blockGroups.isEmpty() && taskSummaries.isEmpty() && sleepEntries.isEmpty() && allCycles.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No history yet.\n\nCompleted sessions, tasks, and sleep entries will appear here.",
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

        // ── Blocks section ────────────────────────────────────────────────────
        if (blockGroups.isNotEmpty()) {
            item("blocks_label") {
                SectionLabel(text = "Blocks")
            }

            blockGroups.forEach { group ->
                val accent = group.colorArgb?.let { Color(it) } ?: primary
                val taskStats = allTaskStats[group.blockId] ?: emptyMap()

                item(key = group.blockId) {
                    HistorySectionHeader(
                        title = group.blockName,
                        subtitle = "${group.sessions.size} ${if (group.sessions.size == 1) "session" else "sessions"}" +
                            if (group.lastDate.isNotEmpty()) " · last ${group.lastDate}" else "",
                        accentColor = accent,
                        expanded = group.blockId in expandedIds,
                        onToggle = { expandedIds = expandedIds.toggle(group.blockId) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }

                if (group.blockId in expandedIds) {
                    item(key = "${group.blockId}_sh") {
                        SubSectionLabel("Sessions")
                    }
                    items(group.sessions, key = { "${group.blockId}_s_${it.startedAtMs}" }) { session ->
                        BlockSessionRow(
                            session = session,
                            onEdit = { editSession = session },
                            onDelete = {
                                blockSessionLogStore?.deleteEntry(session.blockId, session.startedAtMs)
                                localKey++
                            }
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }

                    if (taskStats.isNotEmpty()) {
                        item(key = "${group.blockId}_th") {
                            SubSectionLabel("Block tasks")
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

                    item(key = "${group.blockId}_space") {
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        // ── Tasks section ─────────────────────────────────────────────────────
        if (taskSummaries.isNotEmpty()) {
            item("tasks_label") {
                SectionLabel(text = "Tasks")
            }

            taskSummaries.forEach { summary ->
                item(key = "task_${summary.taskId}") {
                    HistorySectionHeader(
                        title = summary.title,
                        subtitle = buildString {
                            append("${summary.count} ${if (summary.count == 1) "run" else "runs"}")
                            summary.avgMinutes?.let { append(" · avg ${it}m") }
                        },
                        accentColor = primary,
                        expanded = summary.taskId in expandedIds,
                        onToggle = { expandedIds = expandedIds.toggle(summary.taskId) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }

                if (summary.taskId in expandedIds) {
                    items(summary.executions, key = { "exec_${summary.taskId}_${it.startMillis}" }) { exec ->
                        TaskExecutionRow(
                            execution = exec,
                            onEdit = { drillTaskTarget = summary; },
                            onDelete = {
                                taskManager.executions.clear(exec.taskId, exec.startMillis)
                                localKey++
                                val remaining = summary.executions.filter { it.startMillis != exec.startMillis }
                                if (remaining.isEmpty()) expandedIds = expandedIds - summary.taskId
                            }
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                    item(key = "task_${summary.taskId}_space") {
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        // ── Sleep section ─────────────────────────────────────────────────────
        if (sleepEntries.isNotEmpty()) {
            item("sleep_label") {
                SectionLabel(text = "Sleep")
            }
            item("sleep_header") {
                HistorySectionHeader(
                    title = "Sleep",
                    subtitle = buildString {
                        append("${sleepEntries.size} ${if (sleepEntries.size == 1) "entry" else "entries"}")
                        sleepAvgMins?.let { avg ->
                            val h = avg / 60; val m = avg % 60
                            append(" · avg ${if (m == 0) "${h}h" else "${h}h ${m}m"}")
                        }
                    },
                    accentColor = secondary,
                    expanded = "sleep" in expandedIds,
                    onToggle = { expandedIds = expandedIds.toggle("sleep") }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            }

            if ("sleep" in expandedIds) {
                items(sleepEntries, key = { "sleep_${it.dateIso}" }) { entry ->
                    SleepLogRow(
                        entry = entry,
                        onEdit = { editSleepEntry = entry },
                        onDelete = {
                            sleepLogStore.deleteEntry(entry.dateIso)
                            localKey++
                        }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
                item("sleep_space") { Spacer(Modifier.height(4.dp)) }
            }
        }

        // ── Cycles section ────────────────────────────────────────────────────
        if (allCycles.isNotEmpty() || true) { // always show cycles so user can start one
            item("cycles_label") {
                SectionLabel(text = "Cycles")
            }
            item("cycles_header") {
                HistorySectionHeader(
                    title = "Cycles",
                    subtitle = if (allCycles.isEmpty()) "No cycles yet"
                               else "${allCycles.size} ${if (allCycles.size == 1) "cycle" else "cycles"}" +
                                   if (currentCycle != null) " · active now" else "",
                    accentColor = tertiary,
                    expanded = "cycles" in expandedIds,
                    onToggle = { expandedIds = expandedIds.toggle("cycles") },
                    trailingButton = if (currentCycle == null) ({
                        TextButton(
                            onClick = { cycleTracker.manualStart(); localKey++ },
                            modifier = Modifier.padding(end = 4.dp)
                        ) { Text("Start", style = MaterialTheme.typography.labelSmall) }
                    }) else null
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            }

            if ("cycles" in expandedIds) {
                if (currentCycle != null) {
                    item("cycle_active") {
                        CycleRow(
                            cycle = currentCycle,
                            isActive = true,
                            tick = tick,
                            onClick = { editCycle = currentCycle }
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
                items(historyCycles, key = { "cycle_${it.id}" }) { cycle ->
                    CycleRow(
                        cycle = cycle,
                        isActive = false,
                        tick = 0,
                        onClick = { editCycle = cycle }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
                item("cycles_space") { Spacer(Modifier.height(4.dp)) }
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    drillTaskTarget?.let { summary ->
        TaskDrillInSheet(
            title = summary.title,
            executions = summary.executions,
            onUpdate = { updated ->
                taskManager.executions.update(updated)
                localKey++
            },
            onDelete = { exec ->
                taskManager.executions.clear(exec.taskId, exec.startMillis)
                localKey++
            },
            onDismiss = { drillTaskTarget = null; localKey++ }
        )
    }

    measurementDrillTarget?.let { (taskTitle, records) ->
        MeasurementDrillInSheet(
            title = taskTitle,
            records = records,
            onDelete = { rec ->
                val session = blockSessionLogStore?.loadAll()?.find {
                    it.blockId == rec.blockId && it.startedAtMs == rec.sessionStartedAtMs
                }
                if (session != null) {
                    val updated = session.copy(
                        taskMeasurements = session.taskMeasurements.filter {
                            !(it.taskId == rec.measurement.taskId && it.startMs == rec.measurement.startMs)
                        }
                    )
                    blockSessionLogStore?.updateEntry(updated)
                    localKey++
                    val remaining = records.filter { it.measurement.startMs != rec.measurement.startMs }
                    measurementDrillTarget = if (remaining.isEmpty()) null else taskTitle to remaining
                }
            },
            onUpdate = { rec, newStart, newEnd ->
                val session = blockSessionLogStore?.loadAll()?.find {
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
                    blockSessionLogStore?.updateEntry(updated)
                    localKey++
                    val newRec = rec.copy(measurement = updatedM)
                    measurementDrillTarget = taskTitle to records.map { if (it.measurement.startMs == rec.measurement.startMs) newRec else it }
                }
            },
            onDismiss = { measurementDrillTarget = null; localKey++ }
        )
    }

    editSession?.let { session ->
        EditBlockSessionSheet(
            session = session,
            onDismiss = { editSession = null },
            onSave = { newStart, newEnd ->
                blockSessionLogStore?.updateEntry(session.copy(startedAtMs = newStart, endedAtMs = newEnd))
                editSession = null
                localKey++
            }
        )
    }

    editSleepEntry?.let { entry ->
        EditSleepEntrySheet(
            entry = entry,
            onDismiss = { editSleepEntry = null },
            onSave = { newBed, newWake ->
                sleepLogStore.saveEntry(entry.copy(bedMillis = newBed, wakeMillis = newWake))
                editSleepEntry = null
                localKey++
            }
        )
    }

    editCycle?.let { cycle ->
        CycleLogSheet(
            cycle = cycle,
            onDismiss = { editCycle = null },
            onSave = { updated ->
                cycleTracker.store.save(updated)
                editCycle = null
                localKey++
            },
            onDelete = {
                cycleTracker.store.delete(cycle.id)
                editCycle = null
                localKey++
            }
        )
    }
}

// ── Shared section UI ─────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SubSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun HistorySectionHeader(
    title: String,
    subtitle: String,
    accentColor: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailingButton: (@Composable () -> Unit)? = null
) {
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
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        trailingButton?.invoke()
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse" else "Expand",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

// ── Block session rows ────────────────────────────────────────────────────────

@Composable
private fun BlockSessionRow(
    session: BlockSessionLog,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val durationMins = ((session.endedAtMs - session.startedAtMs) / 60_000L).toInt()
    val h = durationMins / 60; val m = durationMins % 60
    val durText = when { h > 0 && m > 0 -> "${h}h ${m}m"; h > 0 -> "${h}h"; else -> "${m}m" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(text = session.date, style = MaterialTheme.typography.bodySmall)
            Text(
                text = "${fmtMs(session.startedAtMs)} – ${fmtMs(session.endedAtMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(text = durText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (session.tasksTotal > 0) {
                Text(
                    text = "${session.tasksCompleted}/${session.tasksTotal}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Delete", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
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
            Text(text = title, style = MaterialTheme.typography.bodySmall)
            Text(
                text = "$count ${if (count == 1) "entry" else "entries"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            )
        }
        Text(text = "avg ${avgMinutes}m", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp))
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
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
        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, modifier = Modifier.padding(horizontal = 32.dp)) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Edit Session", style = MaterialTheme.typography.titleMedium)
                Text("${session.blockName} · ${session.date}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Start", style = MaterialTheme.typography.bodyMedium)
                    TimePickerChip(value = startTime, onValueChange = { startTime = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("End", style = MaterialTheme.typography.bodyMedium)
                    TimePickerChip(value = endTime, onValueChange = { endTime = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onSave(setTimeOnMs(session.startedAtMs, startTime), setTimeOnMs(session.endedAtMs, endTime)) }) { Text("Save") }
                }
            }
        }
    }
}

// ── Task execution rows ───────────────────────────────────────────────────────

@Composable
private fun TaskExecutionRow(
    execution: TaskExecution,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(text = fmtDate(execution.startMillis), style = MaterialTheme.typography.bodySmall)
            val endStr = execution.endMillis?.let { fmtMs(it) } ?: "--:--"
            Text(
                text = "${fmtMs(execution.startMillis)} – $endStr",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        execution.measuredMinutes?.let { m ->
            Text(text = "${m}m", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Delete", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        }
    }
}

// ── Task drill-in sheet ───────────────────────────────────────────────────────

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
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 12.dp),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = title, style = MaterialTheme.typography.titleMedium)
                        val avgMins = executions.mapNotNull { it.measuredMinutes }.takeIf { it.isNotEmpty() }?.average()?.toInt()
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
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    items(executions, key = { it.startMillis }) { exec ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(text = fmtDate(exec.startMillis), style = MaterialTheme.typography.bodyMedium)
                                val endStr = exec.endMillis?.let { fmtMs(it) } ?: "--:--"
                                Text(
                                    text = "${fmtMs(exec.startMillis)} – $endStr",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            exec.measuredMinutes?.let { m -> Text("${m}m", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            IconButton(onClick = { editTarget = exec }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            }
                            IconButton(onClick = { onDelete(exec) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Delete", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            }
                        }
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
        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, modifier = Modifier.padding(horizontal = 32.dp)) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Edit Entry", style = MaterialTheme.typography.titleMedium)
                Text(fmtDate(execution.startMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Start", style = MaterialTheme.typography.bodyMedium)
                    TimePickerChip(value = startTime, onValueChange = { startTime = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("End", style = MaterialTheme.typography.bodyMedium)
                    TimePickerChip(value = endTime, onValueChange = { endTime = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onSave(setTimeOnMs(execution.startMillis, startTime), setTimeOnMs(execution.endMillis ?: execution.startMillis, endTime)) }) { Text("Save") }
                }
            }
        }
    }
}

// ── Sleep rows ────────────────────────────────────────────────────────────────

@Composable
private fun SleepLogRow(entry: SleepLogEntry, onEdit: () -> Unit, onDelete: () -> Unit) {
    val durationMins = ((entry.wakeMillis - entry.bedMillis) / 60_000L).toInt()
    val h = durationMins / 60; val m = durationMins % 60
    val durText = if (m == 0) "${h}h" else "${h}h ${m}m"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(text = entry.dateIso, style = MaterialTheme.typography.bodySmall)
            Text(
                text = "${fmtMs(entry.bedMillis)} – ${fmtMs(entry.wakeMillis)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        Text(text = durText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Delete", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
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
                Text(entry.dateIso, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
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
                    TextButton(onClick = { onSave(setTimeOnMs(entry.bedMillis, bedTime), setTimeOnMs(entry.wakeMillis, wakeTime)) }) { Text("Save") }
                }
            }
        }
    }
}

// ── Cycle rows ────────────────────────────────────────────────────────────────

@Composable
private fun CycleRow(cycle: Cycle, isActive: Boolean, tick: Int, onClick: () -> Unit) {
    val now = System.currentTimeMillis()
    val durationMs = if (isActive) {
        remember(tick) { (cycle.sleepStartMillis ?: now) - cycle.wakeMillis }
    } else {
        cycle.totalDurationMs
    }
    val h = (durationMs / 3_600_000L).toInt()
    val m = ((durationMs % 3_600_000L) / 60_000L).toInt()
    val durText = if (h > 0) "${h}h ${m}m" else "${m}m"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isActive) {
                    Text(
                        text = "Active",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                }
                Text(text = Cycle.formatDate(cycle.wakeMillis), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = "Wake ${Cycle.formatTime(cycle.wakeMillis)}" +
                    (cycle.sleepStartMillis?.let { "  · Sleep ${Cycle.formatTime(it)}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        Text(text = durText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
    }
}

// ── Measurement drill-in sheet ────────────────────────────────────────────────

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
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 12.dp),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
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
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    items(records, key = { "${it.sessionStartedAtMs}_${it.measurement.startMs}" }) { rec ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(text = rec.sessionDate, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "${fmtMs(rec.measurement.startMs)} – ${fmtMs(rec.measurement.endMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            Text("${rec.measurement.measuredMinutes}m", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(onClick = { editTarget = rec }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            }
                            IconButton(onClick = { onDelete(rec) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Delete", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            }
                        }
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
            onSave = { newStart, newEnd -> onUpdate(rec, newStart, newEnd); editTarget = null }
        )
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
        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, modifier = Modifier.padding(horizontal = 32.dp)) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Edit Entry", style = MaterialTheme.typography.titleMedium)
                Text(record.sessionDate, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Start", style = MaterialTheme.typography.bodyMedium)
                    TimePickerChip(value = startTime, onValueChange = { startTime = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("End", style = MaterialTheme.typography.bodyMedium)
                    TimePickerChip(value = endTime, onValueChange = { endTime = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onSave(setTimeOnMs(record.measurement.startMs, startTime), setTimeOnMs(record.measurement.endMs, endTime)) }) { Text("Save") }
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
