package com.waypoint.app.home

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waypoint.app.planner.BlockTask
import com.waypoint.app.planner.BlockTaskPlacement
import com.waypoint.app.planner.NamedBlock
import com.waypoint.app.planner.NamedBlockStore
import com.waypoint.app.planner.TaskRequest
import com.waypoint.app.script.TaskManagerScript

private val BLOCKS_DAY_ABBREVS = mapOf(
    1 to "Mo", 2 to "Tu", 3 to "We", 4 to "Th", 5 to "Fr", 6 to "Sa", 7 to "Su"
)

@Composable
fun BlocksTab(taskManager: TaskManagerScript) {
    val context = LocalContext.current
    val namedBlockStore = remember { NamedBlockStore(context) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val allBlocks = remember(refreshKey) { namedBlockStore.loadAllBlocks() }

    var showAddBlock by remember { mutableStateOf(false) }
    var editBlock by remember { mutableStateOf<NamedBlock?>(null) }
    var addTaskForBlockId by remember { mutableStateOf<String?>(null) }
    var editBlockTask by remember { mutableStateOf<BlockTask?>(null) }

    var taskRefreshKey by remember { mutableIntStateOf(0) }
    val allTasks = remember(taskRefreshKey) { taskManager.getAllTasks() }
    var showAddTask by remember { mutableStateOf(false) }
    var editTask by remember { mutableStateOf<TaskRequest?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(key = "blocks_header") {
            BlocksSectionHeader(title = "Time Blocks", onAdd = { showAddBlock = true })
        }

        if (allBlocks.isEmpty()) {
            item(key = "blocks_empty") {
                BlocksEmptyHint(
                    "No time blocks yet. Add recurring events like Gym, Work, or Dance lessons — " +
                    "then attach tasks that happen before, during, or after them."
                )
            }
        } else {
            items(allBlocks, key = { "block_${it.id}" }) { block ->
                ExpandableBlockCard(
                    block = block,
                    namedBlockStore = namedBlockStore,
                    parentRefreshKey = refreshKey,
                    onEdit = { editBlock = block },
                    onDelete = { namedBlockStore.deleteBlock(block.id); refreshKey++ },
                    onAddTask = { addTaskForBlockId = block.id },
                    onEditTask = { editBlockTask = it },
                    onDeleteTask = { taskId -> namedBlockStore.deleteTask(taskId); refreshKey++ },
                    onTaskIsAlwaysToggled = { task ->
                        namedBlockStore.saveTask(task.copy(isAlways = !task.isAlways))
                        refreshKey++
                    }
                )
                Spacer(Modifier.height(4.dp))
            }
        }

        item(key = "divider") {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }

        item(key = "tasks_header") {
            BlocksSectionHeader(title = "Floating Tasks", onAdd = { showAddTask = true })
        }

        if (allTasks.isEmpty()) {
            item(key = "tasks_empty") {
                BlocksEmptyHint("No tasks queued. Tap + Add to create floating tasks that the planner places in available time.")
            }
        } else {
            items(allTasks, key = { "ftask_${it.id}" }) { task ->
                FloatingTaskRow(
                    task = task,
                    onEdit = { editTask = task },
                    onDelete = { taskManager.retractTask(task.id); taskRefreshKey++ }
                )
            }
        }
    }

    if (showAddBlock || editBlock != null) {
        NamedBlockSheet(
            initial = editBlock,
            store = namedBlockStore,
            onDismiss = { showAddBlock = false; editBlock = null },
            onSaved = { refreshKey++; showAddBlock = false; editBlock = null }
        )
    }

    val blockIdForTask = addTaskForBlockId ?: editBlockTask?.blockId
    if (blockIdForTask != null) {
        AddTaskSheet(
            forBlock = blockIdForTask,
            initialBlockTask = editBlockTask,
            onDismiss = { addTaskForBlockId = null; editBlockTask = null },
            onSaveBlockTask = { task ->
                namedBlockStore.saveTask(task)
                refreshKey++
                addTaskForBlockId = null
                editBlockTask = null
            }
        )
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

// ── Expandable block card ─────────────────────────────────────────────────────

@Composable
private fun ExpandableBlockCard(
    block: NamedBlock,
    namedBlockStore: NamedBlockStore,
    parentRefreshKey: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddTask: () -> Unit,
    onEditTask: (BlockTask) -> Unit,
    onDeleteTask: (String) -> Unit,
    onTaskIsAlwaysToggled: (BlockTask) -> Unit
) {
    var expanded by remember(block.id) { mutableStateOf(false) }
    val blockTasks = remember(block.id, parentRefreshKey, expanded) {
        if (expanded) namedBlockStore.loadTasksForBlock(block.id)
            .sortedWith(compareBy({ it.placement.ordinal }, { -it.priority }))
        else emptyList()
    }
    val accent = block.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary

    val scheduleLabel = when {
        block.isFloating -> buildString {
            append("Auto-place")
            val conds = block.floatingConditions
            if (conds.any { it.type == "workDayOnly" }) append(" · work days")
            if (conds.any { it.type == "dayOffOnly" }) append(" · days off")
            val tw = conds.firstOrNull { it.type == "timeWindow" }
            if (tw?.start != null || tw?.end != null) append(" · ${tw?.start ?: "–"}–${tw?.end ?: "–"}")
        }
        block.recurringDays.isEmpty() -> "No schedule"
        else -> {
            val dayStr = block.recurringDays.sorted()
                .mapNotNull { BLOCKS_DAY_ABBREVS[it] }.joinToString(" ")
            val startStr = "%02d:%02d".format(block.defaultStartHour, block.defaultStartMinute)
            "$dayStr · $startStr"
        }
    }
    val durLabel = durationLabel(block.estimatedMinutes)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            // ── Block header row ──────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(56.dp)
                        .background(
                            accent,
                            RoundedCornerShape(topStart = 12.dp, bottomStart = if (expanded) 0.dp else 12.dp)
                        )
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = block.name,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (block.isFloating) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = accent.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    "auto",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color = accent,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "$durLabel · $scheduleLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Edit, "Edit block",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Delete, "Delete block",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                    )
                }
            }

            // ── Expanded task list ────────────────────────────────────────────
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                    if (blockTasks.isEmpty()) {
                        Text(
                            "No tasks attached to this block.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    } else {
                        blockTasks.forEach { task ->
                            BlockTaskRow(
                                task = task,
                                accent = accent,
                                onEdit = { onEditTask(task) },
                                onDelete = { onDeleteTask(task.id) },
                                onToggleIsAlways = { onTaskIsAlwaysToggled(task) }
                            )
                        }
                    }
                    // Add task button
                    TextButton(
                        onClick = onAddTask,
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add task", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

// ── Block task row ────────────────────────────────────────────────────────────

@Composable
private fun BlockTaskRow(
    task: BlockTask,
    accent: Color,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleIsAlways: () -> Unit
) {
    val placementLabel = when (task.placement) {
        BlockTaskPlacement.BEFORE -> "before"
        BlockTaskPlacement.DURING -> "during"
        BlockTaskPlacement.AFTER  -> "after"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Placement badge
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = accent.copy(alpha = 0.12f)
        ) {
            Text(
                text = placementLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = accent.copy(alpha = 0.85f),
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = durationLabel(task.durationMinutes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
        // Always/situational toggle
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(
                checked = task.isAlways,
                onCheckedChange = { onToggleIsAlways() }
            )
            Text(
                text = if (task.isAlways) "always" else "manual",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Edit, "Edit task",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Delete, "Delete task",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
            )
        }
    }
}

// ── Floating task row ─────────────────────────────────────────────────────────

@Composable
private fun FloatingTaskRow(
    task: TaskRequest,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val priorityColor = when {
        task.priority >= 9 -> Color(0xFFE53935)
        task.priority >= 7 -> Color(0xFFFF7043)
        task.priority >= 4 -> Color(0xFFFFA726)
        else               -> Color(0xFF78909C)
    }
    val conditionSummary = buildString {
        if (task.conditions.any { it.type == "workDayOnly" }) append("work days · ")
        if (task.conditions.any { it.type == "dayOffOnly" }) append("days off · ")
        if (task.conditions.any { it.type == "beforeShift" }) append("before shift · ")
        if (task.conditions.any { it.type == "duringShift" }) append("during shift · ")
        if (task.conditions.any { it.type == "afterShift" }) append("after shift · ")
        val dow = task.conditions.firstOrNull { it.type == "daysOfWeek" }?.days
        if (dow != null) append(dow.sorted().mapNotNull { BLOCKS_DAY_ABBREVS[it] }.joinToString("") + " · ")
    }.trimEnd(' ', '·')

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(priorityColor)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (conditionSummary.isNotEmpty()) {
                Text(
                    text = conditionSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
        Text(
            text = durationLabel(task.durationMinutes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.padding(end = 4.dp)
        )
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Edit, "Edit task", modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Delete, "Delete task", modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
        }
    }
}

// ── Section header ────────────────────────────────────────────────────────────

@Composable
private fun BlocksSectionHeader(title: String, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onBackground
        )
        TextButton(onClick = onAdd) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(2.dp))
            Text("Add", style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ── Empty hint ────────────────────────────────────────────────────────────────

@Composable
private fun BlocksEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
    )
}

// ── Shared duration label ─────────────────────────────────────────────────────

private fun durationLabel(minutes: Int): String = when {
    minutes < 60          -> "${minutes}m"
    minutes % 60 == 0     -> "${minutes / 60}h"
    else                  -> "${minutes / 60}h ${minutes % 60}m"
}
