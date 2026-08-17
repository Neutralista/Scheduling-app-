package com.waypoint.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waypoint.app.planner.EventCategory
import com.waypoint.app.planner.ScheduledEvent
import com.waypoint.app.planner.TaskRequest
import com.waypoint.app.signal.CalendarEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

sealed interface TimelineDetailItem {
    data class CalEvent(val event: CalendarEvent) : TimelineDetailItem
    data class PlannerItem(val scheduled: ScheduledEvent, val task: TaskRequest?) : TimelineDetailItem
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineEventDetailSheet(
    item: TimelineDetailItem,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onStart: (() -> Unit)? = null,
    onComplete: (() -> Unit)? = null,
    onBlockStart: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onPlan: (() -> Unit)? = null,
    onSleepMode: (() -> Unit)? = null,
    isSleepModeActive: Boolean = false,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val deleteTitle = when (item) {
        is TimelineDetailItem.PlannerItem -> item.task?.title ?: item.scheduled.event.title
        is TimelineDetailItem.CalEvent -> item.event.title
    }
    if (showDeleteConfirm && onDelete != null) {
        TaskDeleteDialog(
            taskTitle = deleteTitle,
            onDelete = { onDelete(); onDismiss() },
            onDismiss = { showDeleteConfirm = false }
        )
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            when (item) {
                is TimelineDetailItem.CalEvent ->
                    CalEventContent(item.event)
                is TimelineDetailItem.PlannerItem ->
                    PlannerContent(item.scheduled, item.task)
            }

            // Primary action buttons (start / complete / start block / sleep mode)
            val hasActions = onStart != null || onComplete != null || onBlockStart != null || onResume != null || onPlan != null || onSleepMode != null
            if (hasActions) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (onSleepMode != null) {
                        Button(
                            onClick = { onSleepMode(); onDismiss() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isSleepModeActive) "Stop sleep mode" else "Start sleep mode")
                        }
                    }
                    if (onPlan != null || onBlockStart != null || onResume != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (onPlan != null) {
                                OutlinedButton(
                                    onClick = { onPlan(); onDismiss() },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Plan") }
                            }
                            if (onBlockStart != null) {
                                Button(
                                    onClick = { onBlockStart(); onDismiss() },
                                    modifier = Modifier.weight(1f)
                                ) { Text("▶ Start") }
                            }
                            if (onResume != null) {
                                Button(
                                    onClick = { onResume(); onDismiss() },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Resume") }
                            }
                        }
                    }
                    if (onStart != null) {
                        OutlinedButton(
                            onClick = { onStart(); onDismiss() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Start")
                        }
                    }
                    if (onComplete != null) {
                        FilledTonalButton(
                            onClick = { onComplete(); onDismiss() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Done")
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))

            // Secondary actions (close / edit / skip / delete)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("Close") }
                if (onEdit != null) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onEdit) { Text("Edit") }
                }
                if (onSkip != null) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { onSkip(); onDismiss() }) { Text("Skip") }
                }
                if (onDelete != null) {
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) { Text("Delete") }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CalEventContent(event: CalendarEvent) {
    val calColor = if (event.calendarColor != 0) Color(event.calendarColor)
                   else MaterialTheme.colorScheme.primary

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(calColor)
        )
        Text(
            text = event.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }

    Spacer(Modifier.height(12.dp))

    val dateStr = remember(event.startMillis) {
        Instant.ofEpochMilli(event.startMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(DateTimeFormatter.ofPattern("EEE, MMMM d, yyyy", Locale.getDefault()))
    }
    InfoRow(label = "Date", value = dateStr)
    Spacer(Modifier.height(4.dp))

    if (event.allDay) {
        InfoRow(label = "Time", value = "All day")
    } else {
        InfoRow(label = "Time", value = "${fmtMs(event.startMillis)} – ${fmtMs(event.endMillis)}")
        Spacer(Modifier.height(4.dp))
        InfoRow(label = "Duration", value = formatDuration(((event.endMillis - event.startMillis) / 60_000L).toInt()))
    }
}

@Composable
private fun PlannerContent(scheduled: ScheduledEvent, task: TaskRequest?) {
    val isSleep = scheduled.event.category == EventCategory.SLEEP
    val displayTitle = when {
        scheduled.event.isLogged -> scheduled.event.title
        isSleep -> "${scheduled.event.title} · planned"
        else -> scheduled.event.title
    }

    Text(
        text = displayTitle,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )

    Spacer(Modifier.height(12.dp))

    InfoRow(label = "Scheduled", value = "${fmtMs(scheduled.startMillis)} – ${fmtMs(scheduled.endMillis)}")
    Spacer(Modifier.height(4.dp))
    InfoRow(label = "Duration", value = formatDuration(((scheduled.endMillis - scheduled.startMillis) / 60_000L).toInt()))

    if (task != null) {
        Spacer(Modifier.height(4.dp))
        InfoRow(label = "Priority", value = when {
            task.priority <= 3  -> "Low"
            task.priority <= 5  -> "Medium"
            task.priority <= 7  -> "High"
            task.priority <= 9  -> "Critical"
            else                -> "Urgent"
        })
        if (task.sourceScriptId.isNotEmpty() && task.sourceScriptId != "user") {
            Spacer(Modifier.height(4.dp))
            InfoRow(label = "Source", value = task.sourceScriptId)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun fmtMs(ms: Long): String =
    Calendar.getInstance().apply { timeInMillis = ms }
        .let { "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }

private fun formatDuration(minutes: Int): String {
    val h = minutes / 60; val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0          -> "${h}h"
        else           -> "${m}m"
    }
}
