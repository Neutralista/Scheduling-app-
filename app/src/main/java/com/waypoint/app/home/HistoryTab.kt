package com.waypoint.app.home

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waypoint.app.cycle.CycleTracker
import com.waypoint.app.cycle.CyclesTab
import com.waypoint.app.planner.SleepLogEntry
import com.waypoint.app.planner.SleepLogStore
import com.waypoint.app.planner.TaskExecution
import com.waypoint.app.script.TaskManagerScript
import java.util.Calendar

private enum class HistoryCategory { TASKS, SLEEP, CYCLES }

@Composable
fun HistoryTab(
    cycleTracker: CycleTracker,
    taskManager: TaskManagerScript
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
        }
    }
}

// ── Task history ──────────────────────────────────────────────────────────────

@Composable
private fun TaskHistoryContent(taskManager: TaskManagerScript, refreshKey: Int) {
    val executions = remember(refreshKey) {
        taskManager.executions.loadAll()
            .filter { it.endMillis != null }
            .sortedByDescending { it.startMillis }
    }
    val allTasks = remember(refreshKey) { taskManager.getAllTasks().associateBy { it.id } }

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
            TaskExecutionRow(title = title, execution = exec)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        }
    }
}

@Composable
private fun TaskExecutionRow(title: String, execution: TaskExecution) {
    val measuredMins = execution.measuredMinutes
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
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
    }
}

// ── Sleep history ─────────────────────────────────────────────────────────────

@Composable
private fun SleepHistoryContent(context: Context, refreshKey: Int) {
    val logStore = remember { SleepLogStore(context) }
    val entries = remember(refreshKey) { logStore.loadRecent(30) }

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
            SleepLogRow(entry = entry)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        }
    }
}

@Composable
private fun SleepLogRow(entry: SleepLogEntry) {
    val durationMins = ((entry.wakeMillis - entry.bedMillis) / 60_000L).toInt()
    val h = durationMins / 60
    val m = durationMins % 60
    val durText = if (m == 0) "${h}h" else "${h}h ${m}m"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
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
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun fmtMs(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}
