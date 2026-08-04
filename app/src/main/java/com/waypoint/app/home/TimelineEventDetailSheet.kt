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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    onEdit: (() -> Unit)? = null
) {
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

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))

            // Action buttons
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
                if (onDelete != null) {
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onDelete,
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
