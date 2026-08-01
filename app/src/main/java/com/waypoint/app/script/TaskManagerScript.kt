package com.waypoint.app.script

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waypoint.app.AppLogger
import com.waypoint.app.home.AddTaskSheet
import com.waypoint.app.persistence.TaskCompletionStore
import com.waypoint.app.planner.EventPlannerRegistry
import com.waypoint.app.planner.PlannerEvent
import com.waypoint.app.planner.TaskQueueStore
import com.waypoint.app.planner.TaskRequest

class TaskManagerScript(
    private val store: TaskQueueStore,
    private val registry: EventPlannerRegistry,
    val completions: TaskCompletionStore
) : AppScript {

    override val id = "built_in.task_manager"
    override val displayName = "Task Manager"
    override val hasWidget = true

    companion object {
        private const val TAG = "TaskManagerScript"
        const val WIDGET_ID = "task_manager"
    }

    override fun onAttached(env: ScriptEnvironment) {
        syncToRegistry()
    }

    fun submitTask(req: TaskRequest) {
        store.submit(req)
        AppLogger.i(TAG, "submitTask: id=${req.id}")
        syncToRegistry()
    }

    fun retractTask(taskId: String) {
        store.retract(taskId)
        AppLogger.i(TAG, "retractTask: id=$taskId")
        syncToRegistry()
    }

    fun retractBySource(sourceScriptId: String) {
        store.retractBySource(sourceScriptId)
        syncToRegistry()
    }

    fun getAllTasks(): List<TaskRequest> = store.loadAll()

    fun markDone(taskId: String) = completions.markDone(taskId)
    fun unmarkDone(taskId: String) = completions.unmarkDone(taskId)
    fun isDone(taskId: String) = completions.isDone(taskId)

    fun syncToRegistry() {
        registry.unregisterByWidget(WIDGET_ID)
        val tasks = store.loadAll()
        tasks.forEach { req ->
            registry.register(
                PlannerEvent(
                    id = req.id,
                    title = req.title,
                    durationMinutes = req.durationMinutes,
                    priority = req.priority,
                    conditions = req.conditions.mapNotNull { it.toEventCondition() },
                    sourceWidgetId = WIDGET_ID
                )
            )
        }
        AppLogger.i(TAG, "syncToRegistry: registered ${tasks.size} tasks")
    }

    @Composable
    override fun WidgetContent(state: ScriptState?, onStateChange: (ScriptState) -> Unit) {
        var refreshKey by remember { mutableIntStateOf(0) }
        var tasks by remember(refreshKey) { mutableStateOf(store.loadAll()) }
        var showAdd by remember { mutableStateOf(false) }
        var editTarget by remember { mutableStateOf<TaskRequest?>(null) }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Task Manager",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${tasks.size} task${if (tasks.size == 1) "" else "s"} queued",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { showAdd = true }) {
                        Text("+ Add")
                    }
                }

                if (tasks.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No tasks. Tap + Add to create one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        tasks.forEach { req ->
                            TaskRow(
                                req = req,
                                onEdit = { editTarget = req },
                                onDelete = {
                                    retractTask(req.id)
                                    refreshKey++
                                }
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
                    submitTask(req)
                    refreshKey++
                    showAdd = false
                    editTarget = null
                }
            )
        }
    }
}

@Composable
private fun TaskRow(req: TaskRequest, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = priorityColor(req.priority),
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = req.priority.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = req.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (req.sourceScriptId.isNotEmpty()) {
                Text(
                    text = req.sourceScriptId.removePrefix("built_in."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = "${req.durationMinutes}m",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(2.dp))
        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit task",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete task",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun priorityColor(priority: Int) = when {
    priority >= 8 -> MaterialTheme.colorScheme.error
    priority >= 6 -> MaterialTheme.colorScheme.primary
    else          -> MaterialTheme.colorScheme.secondary
}
