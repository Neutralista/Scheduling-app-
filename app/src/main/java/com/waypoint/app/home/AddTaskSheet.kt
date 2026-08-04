package com.waypoint.app.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.waypoint.app.planner.TASK_REF_SLEEP
import com.waypoint.app.planner.SubtaskDef
import com.waypoint.app.planner.TaskConditionSpec
import com.waypoint.app.planner.TaskRequest
import com.waypoint.app.planner.TaskTrigger
import com.waypoint.app.planner.TriggerEvent
import com.waypoint.app.ui.components.TimePickerChip
import java.util.UUID

private enum class DayRelation { ANY, SAME_DAY_AS, NOT_SAME_DAY_AS }
private enum class OrderRelation { ANY, BEFORE_TASK, AFTER_TASK }

private val DURATION_PRESETS = listOf(15, 30, 45, 60, 90, 120)
private val DURATION_LABELS  = listOf("15m", "30m", "45m", "60m", "90m", "2h")

private data class PriorityOption(val label: String, val value: Int)
private val PRIORITY_OPTIONS = listOf(
    PriorityOption("Low", 3),
    PriorityOption("Medium", 5),
    PriorityOption("High", 7),
    PriorityOption("Critical", 9),
    PriorityOption("Urgent", 11)   // above sleep — displaces sleep window when needed
)

private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private val CHAIN_DEADLINE_PRESETS  = listOf(0, 5, 15, 30, 60)
private val CHAIN_DEADLINE_LABELS   = listOf("No deadline", "5m", "15m", "30m", "1h")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddTaskSheet(
    initial: TaskRequest? = null,
    availableTasks: List<TaskRequest> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (TaskRequest) -> Unit
) {
    // ── Parse initial conditions ─────────────────────────────────────────────
    val initConditions = initial?.conditions ?: emptyList()
    val initTw = initConditions.firstOrNull { it.type == "timeWindow" }

    // ── Form state ───────────────────────────────────────────────────────────
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var titleError by remember { mutableStateOf(false) }

    val initDuration = initial?.durationMinutes ?: 30
    var durationMinutes by remember { mutableIntStateOf(if (initDuration in DURATION_PRESETS) initDuration else 30) }
    var customDuration  by remember { mutableStateOf(initial != null && initDuration !in DURATION_PRESETS) }
    var customDurText   by remember { mutableStateOf(if (initial != null && initDuration !in DURATION_PRESETS) initDuration.toString() else "") }
    var customDurError  by remember { mutableStateOf(false) }

    val initPriority = initial?.priority ?: 5
    val closestPriority = PRIORITY_OPTIONS.minByOrNull { kotlin.math.abs(it.value - initPriority) }?.value ?: 5
    var priority by remember { mutableIntStateOf(closestPriority) }

    var dayRelation by remember { mutableStateOf(
        when {
            initConditions.any { it.type == "sameDayAs" }    -> DayRelation.SAME_DAY_AS
            initConditions.any { it.type == "notSameDayAs" } -> DayRelation.NOT_SAME_DAY_AS
            else -> DayRelation.ANY
        }
    ) }
    var dayRelationTaskIds by remember { mutableStateOf(
        initConditions.firstOrNull { it.type == "sameDayAs" || it.type == "notSameDayAs" }
            ?.referenceTaskIds?.toSet() ?: emptySet()
    ) }

    var orderRelation by remember { mutableStateOf(
        when {
            initConditions.any { it.type == "beforeTask" } -> OrderRelation.BEFORE_TASK
            initConditions.any { it.type == "afterTask" }  -> OrderRelation.AFTER_TASK
            else -> OrderRelation.ANY
        }
    ) }
    var orderRelationTaskIds by remember { mutableStateOf(
        initConditions.firstOrNull { it.type == "beforeTask" || it.type == "afterTask" }
            ?.referenceTaskIds?.toSet() ?: emptySet()
    ) }

    var afterTimeEnabled  by remember { mutableStateOf(initTw?.start != null) }
    var afterTime         by remember { mutableStateOf(initTw?.start ?: "09:00") }
    var beforeTimeEnabled by remember { mutableStateOf(initTw?.end != null) }
    var beforeTime        by remember { mutableStateOf(initTw?.end ?: "17:00") }

    val initDays = initConditions.firstOrNull { it.type == "daysOfWeek" }?.days?.toSet() ?: emptySet()
    var selectedDays by remember { mutableStateOf(initDays) }

    // ── Routine & buffer state ───────────────────────────────────────────────
    var isRoutine by remember { mutableStateOf(initial?.isRoutine ?: false) }
    val subtasks = remember { mutableStateListOf<SubtaskDef>().also { it.addAll(initial?.subtasks ?: emptyList()) } }
    var newSubtaskTitle by remember { mutableStateOf("") }
    var newSubtaskDurText by remember { mutableStateOf("15") }

    val bufferPresets = listOf(0, 5, 10, 15, 30)
    val bufferLabels  = listOf("None", "5m", "10m", "15m", "30m")
    val initBuffer = initial?.bufferMinutes ?: 0
    var bufferMinutes by remember { mutableIntStateOf(if (initBuffer in bufferPresets) initBuffer else 0) }
    var customBuffer  by remember { mutableStateOf(initBuffer !in bufferPresets && initBuffer > 0) }
    var customBufText by remember { mutableStateOf(if (initBuffer !in bufferPresets && initBuffer > 0) initBuffer.toString() else "") }

    var useMeasuredDuration by remember { mutableStateOf(initial?.useMeasuredDuration ?: false) }

    // ── Trigger chain state ──────────────────────────────────────────────────
    val triggers = remember { mutableStateListOf<TaskTrigger>().also { it.addAll(initial?.triggers ?: emptyList()) } }
    var showAddChain by remember { mutableStateOf(false) }
    var chainEvent by remember { mutableStateOf(TriggerEvent.TASK_COMPLETED) }
    var chainTargetId by remember { mutableStateOf<String?>(null) }
    var chainDeadline by remember { mutableIntStateOf(0) }
    var chainError by remember { mutableStateOf("") }

    // Tasks eligible as chain targets: all tasks except this one
    val currentId = initial?.id
    val chainTargets = remember(availableTasks) {
        availableTasks.filter { it.id != currentId }
    }

    // ── Save logic ───────────────────────────────────────────────────────────
    fun save() {
        titleError    = title.trim().isEmpty()
        val resolvedDuration = if (customDuration) customDurText.toIntOrNull() ?: 0 else durationMinutes
        customDurError = customDuration && resolvedDuration <= 0
        if (titleError || customDurError) return

        val conditions = buildList {
            when (dayRelation) {
                DayRelation.SAME_DAY_AS    -> if (dayRelationTaskIds.isNotEmpty())
                    add(TaskConditionSpec("sameDayAs", referenceTaskIds = dayRelationTaskIds.sorted()))
                DayRelation.NOT_SAME_DAY_AS -> if (dayRelationTaskIds.isNotEmpty())
                    add(TaskConditionSpec("notSameDayAs", referenceTaskIds = dayRelationTaskIds.sorted()))
                DayRelation.ANY            -> Unit
            }
            when (orderRelation) {
                OrderRelation.BEFORE_TASK -> if (orderRelationTaskIds.isNotEmpty())
                    add(TaskConditionSpec("beforeTask", referenceTaskIds = orderRelationTaskIds.sorted()))
                OrderRelation.AFTER_TASK  -> if (orderRelationTaskIds.isNotEmpty())
                    add(TaskConditionSpec("afterTask", referenceTaskIds = orderRelationTaskIds.sorted()))
                OrderRelation.ANY         -> Unit
            }
            val hasAfter  = afterTimeEnabled  && afterTime.isNotEmpty()
            val hasBefore = beforeTimeEnabled && beforeTime.isNotEmpty()
            if (hasAfter || hasBefore) {
                add(TaskConditionSpec(
                    type  = "timeWindow",
                    start = if (hasAfter)  afterTime  else "00:00",
                    end   = if (hasBefore) beforeTime else "23:59"
                ))
            }
            if (selectedDays.isNotEmpty()) {
                add(TaskConditionSpec("daysOfWeek", days = selectedDays.sorted()))
            }
        }

        val resolvedBuffer = if (customBuffer) customBufText.toIntOrNull() ?: 0 else bufferMinutes

        onSave(
            TaskRequest(
                id                  = initial?.id ?: UUID.randomUUID().toString(),
                title               = title.trim(),
                durationMinutes     = resolvedDuration,
                priority            = priority,
                sourceScriptId      = initial?.sourceScriptId ?: "user",
                conditions          = conditions,
                isRoutine           = isRoutine,
                subtasks            = subtasks.toList(),
                bufferMinutes       = resolvedBuffer,
                useMeasuredDuration = useMeasuredDuration,
                triggers            = triggers.toList()
            )
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // ── Top bar ──────────────────────────────────────────────────
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Text(
                        text = if (initial == null) "Add Task" else "Edit Task",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    TextButton(onClick = ::save) {
                        Text("Save", color = MaterialTheme.colorScheme.primary)
                    }
                }
                HorizontalDivider()

                // ── Form ─────────────────────────────────────────────────────
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Name
                    FormSection(title = "Name") {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it; titleError = false },
                            label = { Text("Task name") },
                            isError = titleError,
                            supportingText = if (titleError) ({ Text("Name is required") }) else null,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    // Duration
                    FormSection(title = "Duration") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            DURATION_PRESETS.forEachIndexed { i, mins ->
                                FilterChip(
                                    selected = !customDuration && durationMinutes == mins,
                                    onClick  = { durationMinutes = mins; customDuration = false },
                                    label    = { Text(DURATION_LABELS[i]) }
                                )
                            }
                            FilterChip(
                                selected = customDuration,
                                onClick  = { customDuration = true },
                                label    = { Text("Other") }
                            )
                        }
                        if (customDuration) {
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = customDurText,
                                onValueChange = { customDurText = it; customDurError = false },
                                label = { Text("Minutes") },
                                isError = customDurError,
                                supportingText = if (customDurError) ({ Text("Enter a positive number") }) else null,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(160.dp),
                                singleLine = true
                            )
                        }
                    }

                    // Priority
                    FormSection(title = "Priority") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            PRIORITY_OPTIONS.forEach { opt ->
                                FilterChip(
                                    selected = priority == opt.value,
                                    onClick  = { priority = opt.value },
                                    label    = { Text(opt.label) }
                                )
                            }
                        }
                    }

                    // Constraints
                    FormSection(title = "Constraints") {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

                            // Day relation
                            ConstraintSubsection("Day") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        FilterChip(
                                            selected = dayRelation == DayRelation.ANY,
                                            onClick  = { dayRelation = DayRelation.ANY; dayRelationTaskIds = emptySet() },
                                            label    = { Text("Any day") }
                                        )
                                        FilterChip(
                                            selected = dayRelation == DayRelation.SAME_DAY_AS,
                                            onClick  = { dayRelation = DayRelation.SAME_DAY_AS },
                                            label    = { Text("Same day as") }
                                        )
                                        FilterChip(
                                            selected = dayRelation == DayRelation.NOT_SAME_DAY_AS,
                                            onClick  = { dayRelation = DayRelation.NOT_SAME_DAY_AS },
                                            label    = { Text("When not scheduled") }
                                        )
                                    }
                                    if (dayRelation != DayRelation.ANY) {
                                        if (chainTargets.isEmpty()) {
                                            Text(
                                                "Add more tasks to use this constraint.",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            )
                                        } else {
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                chainTargets.forEach { task ->
                                                    FilterChip(
                                                        selected = task.id in dayRelationTaskIds,
                                                        onClick  = {
                                                            dayRelationTaskIds =
                                                                if (task.id in dayRelationTaskIds) dayRelationTaskIds - task.id
                                                                else dayRelationTaskIds + task.id
                                                        },
                                                        label = { Text(task.title, style = MaterialTheme.typography.labelSmall) }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Order relative to another task
                            ConstraintSubsection("Order") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        FilterChip(
                                            selected = orderRelation == OrderRelation.ANY,
                                            onClick  = { orderRelation = OrderRelation.ANY; orderRelationTaskIds = emptySet() },
                                            label    = { Text("Any time") }
                                        )
                                        FilterChip(
                                            selected = orderRelation == OrderRelation.BEFORE_TASK,
                                            onClick  = { orderRelation = OrderRelation.BEFORE_TASK },
                                            label    = { Text("Before") }
                                        )
                                        FilterChip(
                                            selected = orderRelation == OrderRelation.AFTER_TASK,
                                            onClick  = { orderRelation = OrderRelation.AFTER_TASK },
                                            label    = { Text("After") }
                                        )
                                    }
                                    if (orderRelation != OrderRelation.ANY) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            // Sleep is always available as an anchor
                                            FilterChip(
                                                selected = TASK_REF_SLEEP in orderRelationTaskIds,
                                                onClick  = {
                                                    orderRelationTaskIds =
                                                        if (TASK_REF_SLEEP in orderRelationTaskIds) orderRelationTaskIds - TASK_REF_SLEEP
                                                        else orderRelationTaskIds + TASK_REF_SLEEP
                                                },
                                                label = { Text("Sleep") }
                                            )
                                            chainTargets.forEach { task ->
                                                FilterChip(
                                                    selected = task.id in orderRelationTaskIds,
                                                    onClick  = {
                                                        orderRelationTaskIds =
                                                            if (task.id in orderRelationTaskIds) orderRelationTaskIds - task.id
                                                            else orderRelationTaskIds + task.id
                                                    },
                                                    label = { Text(task.title, style = MaterialTheme.typography.labelSmall) }
                                                )
                                            }
                                        }
                                        if (orderRelationTaskIds.isEmpty()) {
                                            Text(
                                                "Select at least one anchor.",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            )
                                        }
                                    }
                                }
                            }

                            // Time window
                            ConstraintSubsection("Time window") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Switch(
                                            checked = afterTimeEnabled,
                                            onCheckedChange = { afterTimeEnabled = it }
                                        )
                                        Text(
                                            "After",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (afterTimeEnabled) MaterialTheme.colorScheme.onSurface
                                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (afterTimeEnabled) {
                                            TimePickerChip(
                                                value = afterTime,
                                                onValueChange = { afterTime = it }
                                            )
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Switch(
                                            checked = beforeTimeEnabled,
                                            onCheckedChange = { beforeTimeEnabled = it }
                                        )
                                        Text(
                                            "Before",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (beforeTimeEnabled) MaterialTheme.colorScheme.onSurface
                                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (beforeTimeEnabled) {
                                            TimePickerChip(
                                                value = beforeTime,
                                                onValueChange = { beforeTime = it }
                                            )
                                        }
                                    }
                                }
                            }

                            // Days of week
                            ConstraintSubsection("Days of week") {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    DAY_NAMES.forEachIndexed { i, name ->
                                        val isoDay = i + 1
                                        FilterChip(
                                            selected = isoDay in selectedDays,
                                            onClick  = {
                                                selectedDays = if (isoDay in selectedDays)
                                                    selectedDays - isoDay else selectedDays + isoDay
                                            },
                                            label = { Text(name) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Buffer
                    FormSection(title = "Buffer after") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                bufferPresets.forEachIndexed { i, mins ->
                                    FilterChip(
                                        selected = !customBuffer && bufferMinutes == mins,
                                        onClick  = { bufferMinutes = mins; customBuffer = false },
                                        label    = { Text(bufferLabels[i]) }
                                    )
                                }
                                FilterChip(
                                    selected = customBuffer,
                                    onClick  = { customBuffer = true },
                                    label    = { Text("Custom") }
                                )
                            }
                            if (customBuffer) {
                                OutlinedTextField(
                                    value = customBufText,
                                    onValueChange = { customBufText = it },
                                    label = { Text("Minutes") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(160.dp),
                                    singleLine = true
                                )
                            }
                        }
                    }

                    // Routine & subtasks
                    FormSection(title = "Routine") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        "Routine task",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        "Step through subtasks with individual timers",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                                Switch(checked = isRoutine, onCheckedChange = { isRoutine = it })
                            }

                            if (isRoutine) {
                                // Existing subtasks
                                subtasks.forEachIndexed { i, sub ->
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            "${i + 1}. ${sub.title} (${sub.defaultDurationMinutes}m)",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        IconButton(
                                            onClick = { subtasks.removeAt(i) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Remove subtask",
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            )
                                        }
                                    }
                                }

                                // Add new subtask
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = newSubtaskTitle,
                                        onValueChange = { newSubtaskTitle = it },
                                        label = { Text("Step name") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    OutlinedTextField(
                                        value = newSubtaskDurText,
                                        onValueChange = { newSubtaskDurText = it },
                                        label = { Text("min") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.width(72.dp),
                                        singleLine = true
                                    )
                                    TextButton(onClick = {
                                        val dur = newSubtaskDurText.toIntOrNull() ?: 15
                                        if (newSubtaskTitle.isNotBlank()) {
                                            subtasks.add(
                                                SubtaskDef(
                                                    id = UUID.randomUUID().toString(),
                                                    title = newSubtaskTitle.trim(),
                                                    defaultDurationMinutes = dur.coerceAtLeast(1)
                                                )
                                            )
                                            newSubtaskTitle = ""
                                            newSubtaskDurText = "15"
                                        }
                                    }) { Text("Add") }
                                }
                            }
                        }
                    }

                    // Options
                    FormSection(title = "Options") {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "Use measured duration",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "Adjust scheduled duration based on logged history",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            Switch(
                                checked = useMeasuredDuration,
                                onCheckedChange = { useMeasuredDuration = it }
                            )
                        }
                    }

                    // Chains
                    FormSection(title = "Chains") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (chainTargets.isEmpty()) {
                                Text(
                                    "Add more tasks to define chains between them.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            } else {
                                Text(
                                    "Chain to another task when this task starts or finishes.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )

                                triggers.forEachIndexed { i, trigger ->
                                    val targetTitle = chainTargets.find { it.id == trigger.chainTaskId }?.title
                                        ?: availableTasks.find { it.id == trigger.chainTaskId }?.title
                                        ?: trigger.chainTaskId
                                    val eventLabel = if (trigger.event == TriggerEvent.TASK_COMPLETED) "On finish" else "On start"
                                    val deadlineLabel = if (trigger.deadlineMinutes > 0) " · ${trigger.deadlineMinutes}m deadline" else ""
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            "$eventLabel → $targetTitle$deadlineLabel",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        IconButton(
                                            onClick = { triggers.removeAt(i) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Remove chain",
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            )
                                        }
                                    }
                                }

                                if (!showAddChain) {
                                    TextButton(
                                        onClick = {
                                            showAddChain = true
                                            chainError = ""
                                            chainTargetId = null
                                            chainEvent = TriggerEvent.TASK_COMPLETED
                                            chainDeadline = 0
                                        }
                                    ) { Text("+ Add chain") }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        ConstraintSubsection("Trigger event") {
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                FilterChip(
                                                    selected = chainEvent == TriggerEvent.TASK_COMPLETED,
                                                    onClick  = { chainEvent = TriggerEvent.TASK_COMPLETED; chainError = "" },
                                                    label    = { Text("On finish") }
                                                )
                                                FilterChip(
                                                    selected = chainEvent == TriggerEvent.TASK_STARTED,
                                                    onClick  = { chainEvent = TriggerEvent.TASK_STARTED; chainError = "" },
                                                    label    = { Text("On start") }
                                                )
                                            }
                                        }

                                        ConstraintSubsection("Chain to task") {
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                chainTargets.forEach { task ->
                                                    FilterChip(
                                                        selected = chainTargetId == task.id,
                                                        onClick  = { chainTargetId = task.id; chainError = "" },
                                                        label    = { Text(task.title, style = MaterialTheme.typography.labelSmall) }
                                                    )
                                                }
                                            }
                                        }

                                        ConstraintSubsection("Deadline for chained task") {
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                CHAIN_DEADLINE_PRESETS.forEachIndexed { i, mins ->
                                                    FilterChip(
                                                        selected = chainDeadline == mins,
                                                        onClick  = { chainDeadline = mins },
                                                        label    = { Text(CHAIN_DEADLINE_LABELS[i]) }
                                                    )
                                                }
                                            }
                                        }

                                        if (chainError.isNotEmpty()) {
                                            Text(
                                                chainError,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }

                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(onClick = { showAddChain = false; chainError = "" }) {
                                                Text("Cancel")
                                            }
                                            TextButton(onClick = {
                                                val targetId = chainTargetId
                                                if (targetId == null) {
                                                    chainError = "Select a task to chain to"
                                                    return@TextButton
                                                }
                                                // Circular guard: check if target chains back to us
                                                val targetTask = chainTargets.find { it.id == targetId }
                                                val thisId = initial?.id
                                                if (thisId != null && targetTask?.triggers?.any {
                                                    it.chainTaskId == thisId
                                                } == true) {
                                                    chainError = "Circular chain: that task already chains back to this one"
                                                    return@TextButton
                                                }
                                                triggers.add(
                                                    TaskTrigger(
                                                        event = chainEvent,
                                                        chainTaskId = targetId,
                                                        deadlineMinutes = chainDeadline
                                                    )
                                                )
                                                showAddChain = false
                                                chainError = ""
                                            }) {
                                                Text("Add", color = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        content()
    }
}

@Composable
private fun ConstraintSubsection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}
