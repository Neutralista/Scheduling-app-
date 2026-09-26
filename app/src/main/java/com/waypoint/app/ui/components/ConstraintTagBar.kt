package com.waypoint.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.waypoint.app.WaypointApplication
import com.waypoint.app.planner.ConstraintTags
import com.waypoint.app.planner.NamedBlock
import com.waypoint.app.planner.TASK_REF_SLEEP
import com.waypoint.app.planner.TagKind
import com.waypoint.app.planner.TagNames
import com.waypoint.app.planner.TagView
import com.waypoint.app.planner.TaskRequest
import com.waypoint.app.planner.eventLabel
import com.waypoint.app.planner.views
import com.waypoint.app.signal.CalendarEvent
import java.time.LocalDate

/** How many days ahead calendar events are offered as tag targets. */
private const val EVENT_DAYS_AHEAD = 14L

fun TagKind.icon(): ImageVector = when (this) {
    TagKind.DAYS     -> Icons.Default.DateRange
    TagKind.REPEAT   -> Icons.Default.Repeat
    TagKind.TIME     -> Icons.Default.Schedule
    TagKind.SLEEP    -> Icons.Default.Bedtime
    TagKind.TASK     -> Icons.Default.TaskAlt
    TagKind.BLOCK    -> Icons.Default.ViewAgenda
    TagKind.EVENT    -> Icons.Default.Event
    TagKind.SAME_DAY -> Icons.Default.Link
    TagKind.NOT_WITH -> Icons.Default.LinkOff
}

/**
 * A tag: an icon for what it points at (time, sleep, task, block, event, …) and its label, with
 * an × when it can be removed.
 */
@Composable
fun TagChip(kind: TagKind, label: String, onRemove: (() -> Unit)? = null, small: Boolean = false) {
    val color = MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (small) 0.6f else 1f))
            .then(if (onRemove != null) Modifier.clickable(onClick = onRemove) else Modifier)
            .padding(
                start = if (small) 6.dp else 8.dp,
                end = if (onRemove != null) 4.dp else if (small) 6.dp else 8.dp,
                top = if (small) 2.dp else 5.dp,
                bottom = if (small) 2.dp else 5.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(kind.icon(), contentDescription = null, tint = color, modifier = Modifier.size(if (small) 11.dp else 14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (onRemove != null) {
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Default.Close, contentDescription = "Remove $label", tint = color, modifier = Modifier.size(14.dp))
        }
    }
}

/** A saved item's tags, read-only and small, for showing under it in a list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagSummary(tags: List<TagView>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        tags.forEach { TagChip(it.kind, it.label, small = true) }
    }
}

/** Upcoming calendar events worth tagging: timed, not "Sleep", one per event (its next time). */
@Composable
private fun rememberTaggableEvents(given: List<CalendarEvent>): List<CalendarEvent> {
    val context = LocalContext.current
    var upcoming by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    LaunchedEffect(Unit) {
        upcoming = runCatching {
            val cal = (context.applicationContext as WaypointApplication).env.calendar
            if (cal.hasPermission()) {
                val today = LocalDate.now()
                cal.eventsInRange(today, today.plusDays(EVENT_DAYS_AHEAD))
            } else emptyList()
        }.getOrDefault(emptyList())
    }
    return remember(given, upcoming) {
        (given + upcoming)
            .filter { it.eventId > 0 && !it.allDay && !it.title.equals("sleep", ignoreCase = true) }
            .sortedBy { it.startMillis }
            .distinctBy { it.eventId }
    }
}

/**
 * The one tag editor, used by tasks, block tasks and auto-placed blocks alike: the tags set so
 * far (tap one to remove it), then "Add tag", which offers Days · Repeats · After · Before ·
 * Same day as · Not with · During event. After/Before can point at a time (when [onTimesChange]
 * is given), sleep, a task, a block or a calendar event in the next two weeks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConstraintTagBar(
    tags: ConstraintTags,
    onChange: (ConstraintTags) -> Unit,
    tasks: List<TaskRequest>,
    blocks: List<NamedBlock>,
    calendarEvents: List<CalendarEvent> = emptyList(),
    afterTime: String? = null,
    beforeTime: String? = null,
    onTimesChange: ((after: String?, before: String?) -> Unit)? = null,
    /** A task that can't be picked for After (it would make an impossible window). */
    afterConflicts: (String) -> Boolean = { false },
    beforeConflicts: (String) -> Boolean = { false },
    /** False when the sheet has its own Once · Repeats choice and this is a one-off: no
     *  Days / Repeats tags then. */
    repeatTags: Boolean = true,
    /** Whether Repeats offers "Once" (not where Once is its own choice). */
    offerOnce: Boolean = true
) {
    val events = rememberTaggableEvents(calendarEvents)
    val names = remember(tasks, blocks, events) {
        TagNames(
            task = { id -> tasks.find { it.id == id }?.title },
            block = { id -> blocks.find { it.id == id }?.name },
            event = { id -> events.find { it.eventId == id }?.let { eventLabel(it.title, it.startMillis) } }
        )
    }
    // Remember event titles as they're picked, so the tag keeps its name on other days.
    fun withTitle(t: ConstraintTags, e: CalendarEvent) = t.copy(eventTitles = t.eventTitles + (e.eventId to e.title))

    var picker by remember { mutableStateOf<String?>(null) }
    var timePicker by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (onTimesChange != null) {
                afterTime?.let { TagChip(TagKind.TIME, "After $it", onRemove = { onTimesChange(null, beforeTime) }) }
                beforeTime?.let { TagChip(TagKind.TIME, "Before $it", onRemove = { onTimesChange(afterTime, null) }) }
            }
            tags.views(names)
                .filter { repeatTags || (it.kind != TagKind.DAYS && it.kind != TagKind.REPEAT) }
                .filter { offerOnce || !(it.kind == TagKind.REPEAT && tags.recurrence is com.waypoint.app.planner.RecurrenceRule.OneOff) }
                .forEach { view ->
                TagChip(view.kind, view.label, onRemove = { onChange(view.remove(tags)) })
            }
            if (picker == null) {
                Surface(
                    onClick = { picker = "categories" },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(Modifier.padding(start = 6.dp, end = 10.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text("Add tag", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        val current = picker
        if (current != null) {
            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (current) {
                                "days" -> "Days"
                                "repeat" -> "Repeats"
                                "after" -> "After"
                                "before" -> "Before"
                                "same" -> "Same day as"
                                "notWith" -> "Not with"
                                "during" -> "During event"
                                else -> "Add tag"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (current != "categories") {
                            TextButton(onClick = { picker = "categories" }) { Text("Back", style = MaterialTheme.typography.labelSmall) }
                        }
                        TextButton(onClick = { picker = null }) { Text("Done", style = MaterialTheme.typography.labelSmall) }
                    }

                    when (current) {
                        "categories" -> FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (repeatTags) {
                                CategoryChip(TagKind.DAYS, "Days") { picker = "days" }
                                CategoryChip(TagKind.REPEAT, "Repeats") { picker = "repeat" }
                            }
                            CategoryChip(TagKind.TIME, "After") { picker = "after" }
                            CategoryChip(TagKind.TIME, "Before") { picker = "before" }
                            if (tasks.isNotEmpty()) {
                                CategoryChip(TagKind.SAME_DAY, "Same day as") { picker = "same" }
                                CategoryChip(TagKind.NOT_WITH, "Not with") { picker = "notWith" }
                            }
                            if (events.isNotEmpty()) CategoryChip(TagKind.EVENT, "During event") { picker = "during" }
                        }

                        "days" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { i, name ->
                                val day = i + 1
                                FilterChip(
                                    selected = day in tags.days,
                                    onClick = { onChange(tags.copy(days = if (day in tags.days) tags.days - day else tags.days + day)) },
                                    label = { Text(name) }
                                )
                            }
                        }

                        "repeat" -> RecurrencePicker(
                            value = tags.recurrence,
                            onChange = { onChange(tags.copy(recurrence = it)) },
                            includeDaysOfWeek = false,
                            includeOnce = offerOnce
                        )

                        "after", "before" -> {
                            val after = current == "after"
                            val taskIds = if (after) tags.afterTaskIds else tags.beforeTaskIds
                            fun setTasks(ids: Set<String>) = onChange(if (after) tags.copy(afterTaskIds = ids) else tags.copy(beforeTaskIds = ids))
                            val blockIds = if (after) tags.afterBlockIds else tags.beforeBlockIds
                            fun setBlocks(ids: Set<String>) = onChange(if (after) tags.copy(afterBlockIds = ids) else tags.copy(beforeBlockIds = ids))
                            val eventIds = if (after) tags.afterEventIds else tags.beforeEventIds
                            fun setEvents(t: ConstraintTags, ids: Set<Long>) = onChange(if (after) t.copy(afterEventIds = ids) else t.copy(beforeEventIds = ids))
                            val conflicts = if (after) afterConflicts else beforeConflicts

                            OptionGroup("Time and sleep") {
                                if (onTimesChange != null) {
                                    val time = if (after) afterTime else beforeTime
                                    OptionChip(TagKind.TIME, time ?: "Pick a time", selected = time != null) { timePicker = current }
                                }
                                OptionChip(TagKind.SLEEP, if (after) "Waking up" else "Bedtime", selected = TASK_REF_SLEEP in taskIds) {
                                    setTasks(if (TASK_REF_SLEEP in taskIds) taskIds - TASK_REF_SLEEP else taskIds + TASK_REF_SLEEP)
                                }
                            }
                            if (tasks.isNotEmpty()) OptionGroup("Tasks") {
                                tasks.forEach { t ->
                                    val selected = t.id in taskIds
                                    OptionChip(TagKind.TASK, t.title, selected, enabled = selected || !conflicts(t.id)) {
                                        setTasks(if (selected) taskIds - t.id else taskIds + t.id)
                                    }
                                }
                            }
                            if (blocks.isNotEmpty()) OptionGroup("Blocks") {
                                blocks.forEach { b ->
                                    val selected = b.id in blockIds
                                    OptionChip(TagKind.BLOCK, b.name, selected) { setBlocks(if (selected) blockIds - b.id else blockIds + b.id) }
                                }
                            }
                            if (events.isNotEmpty()) OptionGroup("Calendar events") {
                                events.forEach { e ->
                                    val selected = e.eventId in eventIds
                                    OptionChip(TagKind.EVENT, eventLabel(e.title, e.startMillis), selected) {
                                        setEvents(withTitle(tags, e), if (selected) eventIds - e.eventId else eventIds + e.eventId)
                                    }
                                }
                            }
                        }

                        "same", "notWith" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val same = current == "same"
                            val ids = if (same) tags.sameDayIds else tags.notWithIds
                            tasks.forEach { t ->
                                val selected = t.id in ids
                                OptionChip(TagKind.TASK, t.title, selected) {
                                    val next = if (selected) ids - t.id else ids + t.id
                                    // A task can't be both "same day as" and "not with" this one.
                                    onChange(
                                        if (same) tags.copy(sameDayIds = next, notWithIds = tags.notWithIds - t.id)
                                        else tags.copy(notWithIds = next, sameDayIds = tags.sameDayIds - t.id)
                                    )
                                }
                            }
                        }

                        "during" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            events.forEach { e ->
                                val selected = tags.duringEventId == e.eventId
                                OptionChip(TagKind.EVENT, eventLabel(e.title, e.startMillis), selected) {
                                    onChange(withTitle(tags, e).copy(duringEventId = if (selected) null else e.eventId))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    timePicker?.let { which ->
        val after = which == "after"
        val value = if (after) afterTime else beforeTime
        TimePickerDialog(
            initialHour = value?.substringBefore(":")?.toIntOrNull() ?: if (after) 9 else 22,
            initialMinute = value?.substringAfter(":")?.toIntOrNull() ?: 0,
            onDismiss = { timePicker = null },
            onConfirm = { h, m ->
                val t = "%02d:%02d".format(h, m)
                if (after) onTimesChange?.invoke(t, beforeTime) else onTimesChange?.invoke(afterTime, t)
                timePicker = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}

@Composable
private fun CategoryChip(kind: TagKind, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(kind.icon(), null, modifier = Modifier.size(16.dp)) }
    )
}

@Composable
private fun OptionChip(kind: TagKind, label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(kind.icon(), null, modifier = Modifier.size(14.dp)) },
        colors = if (!enabled) FilterChipDefaults.filterChipColors(
            disabledContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
            disabledLabelColor = MaterialTheme.colorScheme.error
        ) else FilterChipDefaults.filterChipColors()
    )
}
