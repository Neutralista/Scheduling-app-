package com.waypoint.app.home

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
    blockSessionLogStore: BlockSessionLogStore? = null
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
            HistoryCategory.BLOCKS -> BlockHistoryContent(logStore = blockSessionLogStore, refreshKey = refreshKey)
        }
    }
}

// ── Task history ──────────────────────────────────────────────────────────────

@Composable
private fun TaskHistoryContent(taskManager: TaskManagerScript, refreshKey: Int) {
    var localKey by remember { mutableIntStateOf(0) }
    val combinedKey = refreshKey + localKey

    val executions = remember(combinedKey) {
        taskManager.executions.loadAll()
            .filter { it.endMillis != null }
            .sortedByDescending { it.startMillis }
    }
    val allTasks = remember(combinedKey) { taskManager.getAllTasks().associateBy { it.id } }

    if (executions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No tasks completed today.\n\nTap the play button on a task to start the timer.",
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
        items(executions, key = { it.taskId }) { exec ->
            val title = allTasks[exec.taskId]?.title ?: "Unknown task"
            TaskExecutionRow(
                title = title,
                execution = exec,
                onDelete = {
                    taskManager.executions.clear(exec.taskId, exec.startMillis)
                    localKey++
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        }
    }
}

@Composable
private fun TaskExecutionRow(title: String, execution: TaskExecution, onDelete: () -> Unit) {
    val measuredMins = execution.measuredMinutes
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            val startStr = fmtMs(execution.startMillis)
            val endStr = execution.endMillis?.let { fmtMs(it) } ?: "--:--"
            Text(
                text = "$startStr – $endStr",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        if (measuredMins != null) {
            Text(
                text = "${measuredMins}m",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Delete execution",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
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
            Text(
                text = entry.dateIso,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${fmtMs(entry.bedMillis)} – ${fmtMs(entry.wakeMillis)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        Text(
            text = durText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit entry",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Delete entry",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
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
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    text = "Edit Sleep Entry",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = entry.dateIso,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Bed", style = MaterialTheme.typography.bodyMedium)
                    TimePickerChip(value = bedTime, onValueChange = { bedTime = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Wake", style = MaterialTheme.typography.bodyMedium)
                    TimePickerChip(value = wakeTime, onValueChange = { wakeTime = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = {
                        val newBed  = setTimeOnMs(entry.bedMillis, bedTime)
                        val newWake = setTimeOnMs(entry.wakeMillis, wakeTime)
                        onSave(newBed, newWake)
                    }) { Text("Save") }
                }
            }
        }
    }
}

// ── Block session history ─────────────────────────────────────────────────────

@Composable
private fun BlockHistoryContent(logStore: BlockSessionLogStore?, refreshKey: Int) {
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

    val entries = remember(refreshKey) { logStore.loadAll() }

    if (entries.isEmpty()) {
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        items(entries, key = { "${it.blockId}_${it.startedAtMs}" }) { log ->
            BlockSessionLogRow(log = log)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        }
    }
}

@Composable
private fun BlockSessionLogRow(log: BlockSessionLog) {
    val accentColor = log.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val durationMs = log.endedAtMs - log.startedAtMs
    val durationMins = (durationMs / 60_000L).toInt()
    val h = durationMins / 60
    val m = durationMins % 60
    val durText = if (h > 0 && m > 0) "${h}h ${m}m" else if (h > 0) "${h}h" else "${m}m"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(40.dp)
                .background(accentColor, RoundedCornerShape(2.dp))
        )
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = log.blockName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${log.date} · ${fmtMs(log.startedAtMs)} – ${fmtMs(log.endedAtMs)}",
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
            if (log.tasksTotal > 0) {
                Text(
                    text = "${log.tasksCompleted}/${log.tasksTotal} tasks",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun fmtMs(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
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
