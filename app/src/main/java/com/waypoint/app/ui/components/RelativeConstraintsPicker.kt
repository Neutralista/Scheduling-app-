package com.waypoint.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waypoint.app.planner.NamedBlock
import com.waypoint.app.planner.TASK_REF_SLEEP
import com.waypoint.app.planner.TaskConditionSpec
import com.waypoint.app.planner.TaskRequest
import com.waypoint.app.signal.CalendarEvent

internal val RELATIVE_CONDITION_TYPES = setOf(
    "afterTask", "beforeTask", "sameDayAs", "notSameDayAs",
    "afterCalEvent", "beforeCalEvent", "duringCalEvent", "afterBlock", "beforeBlock"
)

/**
 * Tag-based "After / Before / Same day as / During event" constraint picker — the same
 * relationship vocabulary AddTaskSheet's Constraints section already offers for tasks (Sleep,
 * other tasks, calendar events, named blocks), extracted so any schedulable entity built on
 * [TaskConditionSpec] can offer it, not just tasks. A floating NamedBlock is scheduled by the
 * exact same condition system, so there's no reason it should have a narrower vocabulary.
 *
 * Controlled component: reads its current state out of [conditions] (only the relative-condition
 * types listed in [RELATIVE_CONDITION_TYPES] — everything else in the list is left untouched and
 * passed straight through on every change) and emits the full updated list via
 * [onConditionsChange].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RelativeConstraintsPicker(
    conditions: List<TaskConditionSpec>,
    onConditionsChange: (List<TaskConditionSpec>) -> Unit,
    availableTasks: List<TaskRequest> = emptyList(),
    availableBlocks: List<NamedBlock> = emptyList(),
    calendarEvents: List<CalendarEvent> = emptyList(),
    allowSameDayAs: Boolean = true
) {
    val afterTaskIds = conditions.firstOrNull { it.type == "afterTask" }?.referenceTaskIds?.toSet() ?: emptySet()
    val beforeTaskIds = conditions.firstOrNull { it.type == "beforeTask" }?.referenceTaskIds?.toSet() ?: emptySet()
    val afterCalEventIds = conditions.filter { it.type == "afterCalEvent" }.mapNotNull { it.calendarEventId }.toSet()
    val beforeCalEventIds = conditions.filter { it.type == "beforeCalEvent" }.mapNotNull { it.calendarEventId }.toSet()
    val duringCalEventId = conditions.firstOrNull { it.type == "duringCalEvent" }?.calendarEventId
    val afterBlockId = conditions.firstOrNull { it.type == "afterBlock" }?.blockId
    val beforeBlockId = conditions.firstOrNull { it.type == "beforeBlock" }?.blockId
    val sameDayAsIds = conditions.firstOrNull { it.type == "sameDayAs" }?.referenceTaskIds?.toSet() ?: emptySet()
    val notSameDayAsIds = conditions.firstOrNull { it.type == "notSameDayAs" }?.referenceTaskIds?.toSet() ?: emptySet()
    val dayRelation = when { sameDayAsIds.isNotEmpty() -> "same"; notSameDayAsIds.isNotEmpty() -> "not"; else -> "any" }
    val dayRelationIds = if (dayRelation == "same") sameDayAsIds else notSameDayAsIds

    fun emit(
        afterTask: Set<String> = afterTaskIds,
        beforeTask: Set<String> = beforeTaskIds,
        afterCal: Set<Long> = afterCalEventIds,
        beforeCal: Set<Long> = beforeCalEventIds,
        duringCal: Long? = duringCalEventId,
        afterBlk: String? = afterBlockId,
        beforeBlk: String? = beforeBlockId,
        dayRel: String = dayRelation,
        dayRelIds: Set<String> = dayRelationIds
    ) {
        val newRelevant = buildList {
            if (afterTask.isNotEmpty()) add(TaskConditionSpec("afterTask", referenceTaskIds = afterTask.sorted()))
            if (beforeTask.isNotEmpty()) add(TaskConditionSpec("beforeTask", referenceTaskIds = beforeTask.sorted()))
            afterCal.forEach { add(TaskConditionSpec("afterCalEvent", calendarEventId = it)) }
            beforeCal.forEach { add(TaskConditionSpec("beforeCalEvent", calendarEventId = it)) }
            duringCal?.let { add(TaskConditionSpec("duringCalEvent", calendarEventId = it)) }
            afterBlk?.let { add(TaskConditionSpec("afterBlock", blockId = it)) }
            beforeBlk?.let { add(TaskConditionSpec("beforeBlock", blockId = it)) }
            if (dayRel == "same" && dayRelIds.isNotEmpty()) add(TaskConditionSpec("sameDayAs", referenceTaskIds = dayRelIds.sorted()))
            if (dayRel == "not"  && dayRelIds.isNotEmpty()) add(TaskConditionSpec("notSameDayAs", referenceTaskIds = dayRelIds.sorted()))
        }
        onConditionsChange(conditions.filterNot { it.type in RELATIVE_CONDITION_TYPES } + newRelevant)
    }

    // null = closed; "categories" = category list; else = one specific category
    var picker by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val hasAny = afterTaskIds.isNotEmpty() || beforeTaskIds.isNotEmpty() ||
            afterCalEventIds.isNotEmpty() || beforeCalEventIds.isNotEmpty() ||
            duringCalEventId != null || afterBlockId != null || beforeBlockId != null ||
            dayRelationIds.isNotEmpty()

        if (hasAny) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                afterTaskIds.forEach { tid ->
                    val name = if (tid == TASK_REF_SLEEP) "Sleep" else availableTasks.find { it.id == tid }?.title ?: "Deleted task"
                    RcpTag("After $name") { emit(afterTask = afterTaskIds - tid) }
                }
                beforeTaskIds.forEach { tid ->
                    val name = if (tid == TASK_REF_SLEEP) "Sleep" else availableTasks.find { it.id == tid }?.title ?: "Deleted task"
                    RcpTag("Before $name") { emit(beforeTask = beforeTaskIds - tid) }
                }
                afterCalEventIds.forEach { eid ->
                    val name = calendarEvents.find { it.eventId == eid }?.title ?: "Calendar event"
                    RcpTag("After $name") { emit(afterCal = afterCalEventIds - eid) }
                }
                beforeCalEventIds.forEach { eid ->
                    val name = calendarEvents.find { it.eventId == eid }?.title ?: "Calendar event"
                    RcpTag("Before $name") { emit(beforeCal = beforeCalEventIds - eid) }
                }
                duringCalEventId?.let { eid ->
                    val name = calendarEvents.find { it.eventId == eid }?.title ?: "Calendar event"
                    RcpTag("During $name") { emit(duringCal = null) }
                }
                afterBlockId?.let { bid ->
                    val name = availableBlocks.find { it.id == bid }?.name ?: "Deleted block"
                    RcpTag("After $name") { emit(afterBlk = null) }
                }
                beforeBlockId?.let { bid ->
                    val name = availableBlocks.find { it.id == bid }?.name ?: "Deleted block"
                    RcpTag("Before $name") { emit(beforeBlk = null) }
                }
                dayRelationIds.forEach { tid ->
                    val name = availableTasks.find { it.id == tid }?.title ?: "Deleted task"
                    val prefix = if (dayRelation == "same") "Same day as" else "Not with"
                    RcpTag("$prefix $name") {
                        val next = dayRelationIds - tid
                        emit(dayRel = if (next.isEmpty()) "any" else dayRelation, dayRelIds = next)
                    }
                }
            }
        }

        if (picker == null) {
            FilterChip(selected = false, onClick = { picker = "categories" }, label = { Text("+ Add constraint") })
        } else {
            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = when (picker) {
                                "categories"  -> "Add constraint"
                                "after"       -> "After"
                                "before"      -> "Before"
                                "sameDayAs"   -> "Day relation"
                                "duringEvent" -> "During event"
                                else          -> "Add constraint"
                            },
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (picker != "categories") {
                                TextButton(onClick = { picker = "categories" }, modifier = Modifier.height(24.dp)) {
                                    Text("Back", style = MaterialTheme.typography.labelSmall)
                                }
                                Spacer(Modifier.width(4.dp))
                            }
                            IconButton(onClick = { picker = null }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    when (picker) {
                        "categories" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(selected = false, onClick = { picker = "after" }, label = { Text("After") })
                            FilterChip(selected = false, onClick = { picker = "before" }, label = { Text("Before") })
                            if (allowSameDayAs) FilterChip(selected = false, onClick = { picker = "sameDayAs" }, label = { Text("Same day as / Not with") })
                            if (calendarEvents.isNotEmpty()) FilterChip(selected = false, onClick = { picker = "duringEvent" }, label = { Text("During event") })
                        }

                        "after" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(
                                selected = TASK_REF_SLEEP in afterTaskIds,
                                onClick = { emit(afterTask = if (TASK_REF_SLEEP in afterTaskIds) afterTaskIds - TASK_REF_SLEEP else afterTaskIds + TASK_REF_SLEEP) },
                                label = { Text("Sleep") }
                            )
                            availableTasks.forEach { task ->
                                FilterChip(
                                    selected = task.id in afterTaskIds,
                                    onClick = { emit(afterTask = if (task.id in afterTaskIds) afterTaskIds - task.id else afterTaskIds + task.id) },
                                    label = { Text(task.title, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                            calendarEvents.forEach { evt ->
                                FilterChip(
                                    selected = evt.eventId in afterCalEventIds,
                                    onClick = { emit(afterCal = if (evt.eventId in afterCalEventIds) afterCalEventIds - evt.eventId else afterCalEventIds + evt.eventId) },
                                    label = { Text(evt.title, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                            availableBlocks.forEach { block ->
                                FilterChip(
                                    selected = afterBlockId == block.id,
                                    onClick = { emit(afterBlk = if (afterBlockId == block.id) null else block.id) },
                                    label = { Text(block.name, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }

                        "before" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(
                                selected = TASK_REF_SLEEP in beforeTaskIds,
                                onClick = { emit(beforeTask = if (TASK_REF_SLEEP in beforeTaskIds) beforeTaskIds - TASK_REF_SLEEP else beforeTaskIds + TASK_REF_SLEEP) },
                                label = { Text("Sleep") }
                            )
                            availableTasks.forEach { task ->
                                FilterChip(
                                    selected = task.id in beforeTaskIds,
                                    onClick = { emit(beforeTask = if (task.id in beforeTaskIds) beforeTaskIds - task.id else beforeTaskIds + task.id) },
                                    label = { Text(task.title, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                            calendarEvents.forEach { evt ->
                                FilterChip(
                                    selected = evt.eventId in beforeCalEventIds,
                                    onClick = { emit(beforeCal = if (evt.eventId in beforeCalEventIds) beforeCalEventIds - evt.eventId else beforeCalEventIds + evt.eventId) },
                                    label = { Text(evt.title, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                            availableBlocks.forEach { block ->
                                FilterChip(
                                    selected = beforeBlockId == block.id,
                                    onClick = { emit(beforeBlk = if (beforeBlockId == block.id) null else block.id) },
                                    label = { Text(block.name, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }

                        "sameDayAs" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilterChip(
                                    selected = dayRelation == "same",
                                    onClick = { emit(dayRel = "same") },
                                    label = { Text("Same day as") }
                                )
                                FilterChip(
                                    selected = dayRelation == "not",
                                    onClick = { emit(dayRel = "not", dayRelIds = emptySet()) },
                                    label = { Text("Not with") }
                                )
                            }
                            if (dayRelation != "any") {
                                if (availableTasks.isEmpty()) {
                                    Text(
                                        "Add more tasks to use this constraint.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                } else {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        availableTasks.forEach { task ->
                                            FilterChip(
                                                selected = task.id in dayRelationIds,
                                                onClick = {
                                                    val next = if (task.id in dayRelationIds) dayRelationIds - task.id else dayRelationIds + task.id
                                                    emit(dayRelIds = next)
                                                },
                                                label = { Text(task.title, style = MaterialTheme.typography.labelSmall) }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        "duringEvent" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            calendarEvents.forEach { evt ->
                                FilterChip(
                                    selected = duringCalEventId == evt.eventId,
                                    onClick = { emit(duringCal = if (duringCalEventId == evt.eventId) null else evt.eventId) },
                                    label = { Text(evt.title, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RcpTag(label: String, onRemove: () -> Unit) {
    FilterChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp)) }
    )
}
