package com.waypoint.app.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waypoint.app.planner.CalendarPrefsStore
import com.waypoint.app.planner.EventPlannerRegistry
import com.waypoint.app.planner.NamedBlockStore
import com.waypoint.app.planner.withResolvedSequence
import com.waypoint.app.script.TaskManagerScript
import com.waypoint.app.signal.CalendarEvent
import com.waypoint.app.signal.CalendarSignals
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the add chooser can make. */
enum class AddKind { TASK, BLOCK, EVENT }

/**
 * The one "add" flow used everywhere something can be added: first a choice of Task, Block or
 * Event (like the timeline's free-slot sheet), then that item's sheet. With [slot] the choice
 * shows the free time it was opened on, and a new event starts there. Inside a block
 * ([forBlockId]) only a task of that block makes sense, so the choice is skipped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAnythingSheet(
    date: LocalDate,
    taskManager: TaskManagerScript,
    namedBlockStore: NamedBlockStore,
    eventPlanner: EventPlannerRegistry?,
    calendarSignals: CalendarSignals?,
    calendarPrefs: CalendarPrefsStore?,
    calendarEvents: List<CalendarEvent> = emptyList(),
    slot: Pair<Long, Long>? = null,
    forBlockId: String? = null,
    onDismiss: () -> Unit,
    /** Something was saved; the caller refreshes what it shows. */
    onAdded: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(if (forBlockId != null) AddKind.TASK else null) }
    val allBlocks = remember { namedBlockStore.loadAllBlocks() }
    val allTasks = remember { taskManager.getAllTasks() }

    when (kind) {
        null -> ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                val subtitle = remember(slot, date) {
                    if (slot != null) {
                        val sdf = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
                        "${sdf.format(java.util.Date(slot.first))} – ${sdf.format(java.util.Date(slot.second))}"
                    } else if (date == LocalDate.now()) "Today"
                    else date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AddKindButton("+ Task", { kind = AddKind.TASK }, Modifier.weight(1f))
                    AddKindButton("+ Block", { kind = AddKind.BLOCK }, Modifier.weight(1f))
                    if (calendarSignals != null) {
                        AddKindButton("+ Event", { kind = AddKind.EVENT }, Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        AddKind.TASK -> AddTaskSheet(
            initial = null,
            availableTasks = allTasks,
            calendarEvents = calendarEvents,
            availableBlocks = allBlocks.filter { it.id != forBlockId },
            forBlock = forBlockId,
            blockPhases = forBlockId?.let { id -> allBlocks.find { it.id == id } }?.phases.orEmpty(),
            eventPlanner = eventPlanner,
            namedBlockStore = namedBlockStore,
            onDismiss = onDismiss,
            onSave = { req ->
                taskManager.submitTask(req)
                onAdded()
                onDismiss()
            },
            onSaveBlockTask = if (forBlockId != null) { task ->
                namedBlockStore.saveTask(task.withResolvedSequence(namedBlockStore.loadTasksForBlock(task.blockId)))
                onAdded()
                onDismiss()
            } else null
        )

        AddKind.BLOCK -> NamedBlockSheet(
            initial = null,
            store = namedBlockStore,
            availableTasks = allTasks,
            calendarEvents = calendarEvents,
            onDismiss = onDismiss,
            onSaved = {
                onAdded()
                onDismiss()
            }
        )

        AddKind.EVENT -> AddCalendarEventSheet(
            date = date,
            initialStartMs = slot?.first,
            initialEndMs = slot?.second,
            onDismiss = onDismiss,
            onSave = { title, startMs, endMs, notes, allDay, reservesTime, options ->
                scope.launch {
                    val eventId = calendarSignals?.createEvent(
                        title, startMs, endMs, notes, allDay,
                        rrule = options.rrule, reminderMinutes = options.reminderMinutes
                    ) ?: -1L
                    if (eventId > 0) calendarPrefs?.setReservesTime(eventId, reservesTime)
                    onAdded()
                    onDismiss()
                }
            }
        )
    }
}

@Composable
private fun AddKindButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Every add button in the app: a filled pill with a + so it reads as a button at a glance,
 * not as loose text.
 */
@Composable
fun AddPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
