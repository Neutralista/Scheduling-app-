package com.waypoint.app.planner

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.util.Calendar

@Composable
fun BlockScopeView(
    session: ActiveBlockSession,
    namedBlockStore: NamedBlockStore,
    date: LocalDate,
    modifier: Modifier = Modifier,
    onEndSession: () -> Unit
) {
    val blockColor = session.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val tasks = remember(session.blockId, date) {
        namedBlockStore.resolveActiveTasks(session.blockId, date)
    }
    val checkedIds = remember { mutableStateOf(emptySet<String>()) }

    Column(modifier.fillMaxSize()) {
        BlockScopeHeader(
            session = session,
            blockColor = blockColor,
            totalTasks = tasks.size,
            checkedTasks = checkedIds.value.size,
            onEndSession = onEndSession
        )
        HorizontalDivider(color = blockColor.copy(alpha = 0.25f))

        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No tasks scheduled for this block",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        } else {
            val before = tasks.filter { it.placement == BlockTaskPlacement.BEFORE }
                .sortedByDescending { it.priority }
            val during = tasks.filter { it.placement == BlockTaskPlacement.DURING }
                .sortedByDescending { it.priority }
            val after  = tasks.filter { it.placement == BlockTaskPlacement.AFTER }
                .sortedByDescending { it.priority }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (before.isNotEmpty()) {
                    TaskSection(label = "Before", tasks = before, blockColor = blockColor,
                        checkedIds = checkedIds.value) { id ->
                        checkedIds.value = checkedIds.value.toggle(id)
                    }
                }
                if (during.isNotEmpty()) {
                    TaskSection(label = "During", tasks = during, blockColor = blockColor,
                        checkedIds = checkedIds.value) { id ->
                        checkedIds.value = checkedIds.value.toggle(id)
                    }
                }
                if (after.isNotEmpty()) {
                    TaskSection(label = "After", tasks = after, blockColor = blockColor,
                        checkedIds = checkedIds.value) { id ->
                        checkedIds.value = checkedIds.value.toggle(id)
                    }
                }
            }
        }
    }
}

private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id

@Composable
private fun BlockScopeHeader(
    session: ActiveBlockSession,
    blockColor: Color,
    totalTasks: Int,
    checkedTasks: Int,
    onEndSession: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(blockColor)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = session.blockName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = blockColor
            )
            val progress = if (totalTasks > 0) "  ·  $checkedTasks/$totalTasks done" else ""
            Text(
                text = "${bsFmt(session.startedAtMs)} – ${bsFmt(session.scheduledEndMs)}$progress",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = onEndSession,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, blockColor.copy(alpha = 0.55f))
        ) {
            Text(
                "End Block",
                style = MaterialTheme.typography.labelSmall,
                color = blockColor
            )
        }
    }
}

@Composable
private fun TaskSection(
    label: String,
    tasks: List<BlockTask>,
    blockColor: Color,
    checkedIds: Set<String>,
    onToggle: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
            color = blockColor.copy(alpha = 0.65f),
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp, start = 4.dp)
        )
        tasks.forEach { task ->
            TaskRow(task = task, checked = task.id in checkedIds, blockColor = blockColor) {
                onToggle(task.id)
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: BlockTask,
    checked: Boolean,
    blockColor: Color,
    onToggle: () -> Unit
) {
    val onSV = MaterialTheme.colorScheme.onSurfaceVariant
    val bg = if (checked) blockColor.copy(alpha = 0.08f)
             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(
                width = 1.dp,
                color = if (checked) blockColor.copy(alpha = 0.25f) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onToggle() }
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = blockColor,
                uncheckedColor = onSV.copy(alpha = 0.38f)
            ),
            modifier = Modifier.size(40.dp)
        )
        Text(
            text = task.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (checked) onSV.copy(alpha = 0.45f) else onSV,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        val durMin = task.durationMinutes
        Text(
            text = when {
                durMin < 60            -> "${durMin}m"
                durMin % 60 == 0       -> "${durMin / 60}h"
                else                   -> "${durMin / 60}h ${durMin % 60}m"
            },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = onSV.copy(alpha = 0.38f)
        )
    }
}

private fun bsFmt(ms: Long): String =
    Calendar.getInstance().apply { timeInMillis = ms }
        .let { "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }
