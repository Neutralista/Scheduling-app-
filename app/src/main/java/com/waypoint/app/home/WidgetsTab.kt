package com.waypoint.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waypoint.app.planner.TaskRequest
import com.waypoint.app.script.AppScript
import com.waypoint.app.script.ScriptState
import com.waypoint.app.script.TaskManagerScript

@Composable
fun WidgetsTab(
    widgets: List<AppScript>,
    statesById: Map<String, ScriptState>,
    onStateChange: (scriptId: String, newState: ScriptState) -> Unit,
    taskManager: TaskManagerScript
) {
    var taskRefreshKey by remember { mutableIntStateOf(0) }
    val allTasks by remember(taskRefreshKey) { mutableStateOf(taskManager.getAllTasks()) }
    var showAddTask by remember { mutableStateOf(false) }
    var editTask by remember { mutableStateOf<TaskRequest?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Task Queue section ───────────────────────────────────────────────
        item(key = "tasks_header") {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Task Queue",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showAddTask = true }) { Text("+ Add") }
            }
        }

        if (allTasks.isEmpty()) {
            item(key = "tasks_empty") {
                Text(
                    "No tasks queued. Tap + Add to create one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        } else {
            items(allTasks, key = { "task_${it.id}" }) { task ->
                TaskRow(
                    task = task,
                    onEdit = { editTask = task },
                    onDelete = {
                        taskManager.retractTask(task.id)
                        taskRefreshKey++
                    }
                )
            }
        }

        item(key = "divider1") {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        // ── Modules / Widgets section ────────────────────────────────────────
        item(key = "modules_header") {
            Text(
                "Modules",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        if (widgets.isEmpty()) {
            item(key = "modules_empty") {
                Text(
                    "Scripts that declare a widget will appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(widgets, key = { it.id }) { script ->
                script.WidgetContent(
                    state = statesById[script.id],
                    onStateChange = { newState -> onStateChange(script.id, newState) }
                )
            }
        }

        item(key = "bottom_spacer") { Spacer(Modifier.height(16.dp)) }
    }

    if (showAddTask || editTask != null) {
        AddTaskSheet(
            initial = editTask,
            availableTasks = allTasks,
            onDismiss = { showAddTask = false; editTask = null },
            onSave = { req ->
                taskManager.submitTask(req)
                taskRefreshKey++
                showAddTask = false
                editTask = null
            }
        )
    }
}

@Composable
private fun TaskRow(
    task: TaskRequest,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    if (showDeleteDialog) {
        TaskDeleteDialog(
            taskTitle = task.title,
            onDelete = onDelete,
            onDismiss = { showDeleteDialog = false }
        )
    }

    val priorityColor = task.colorArgb?.let { Color(it) } ?: when {
        task.priority >= 9 -> Color(0xFFE53935)
        task.priority >= 7 -> Color(0xFFFF7043)
        task.priority >= 4 -> Color(0xFFFFA726)
        else               -> Color(0xFF78909C)
    }
    val durLabel = when {
        task.durationMinutes < 60 -> "${task.durationMinutes}m"
        task.durationMinutes % 60 == 0 -> "${task.durationMinutes / 60}h"
        else -> "${task.durationMinutes / 60}h ${task.durationMinutes % 60}m"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(priorityColor)
        )
        Text(
            text = task.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        )
        Text(
            text = durLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp)
        )
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Edit, "Edit task", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Delete, "Delete task", modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error)
        }
    }
}
