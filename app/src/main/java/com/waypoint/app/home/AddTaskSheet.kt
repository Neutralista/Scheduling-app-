package com.waypoint.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.waypoint.app.planner.BlockSubPlacement
import com.waypoint.app.planner.BlockTask
import com.waypoint.app.planner.BlockTaskPlacement
import com.waypoint.app.planner.NamedBlock
import com.waypoint.app.planner.PlannerZone
import com.waypoint.app.planner.RecurrenceRule
import com.waypoint.app.planner.TASK_REF_SLEEP
import com.waypoint.app.planner.SubtaskDef
import com.waypoint.app.planner.TaskConditionSpec
import com.waypoint.app.planner.TaskRequest
import com.waypoint.app.planner.TaskTrigger
import com.waypoint.app.planner.TriggerEvent
import com.waypoint.app.signal.CalendarEvent
import com.waypoint.app.ui.components.ColorPreviewSwatch
import com.waypoint.app.ui.components.HsvColorPicker
import com.waypoint.app.ui.components.RecurrencePicker
import com.waypoint.app.ui.components.TimePickerChip
import com.waypoint.app.ui.components.TimePickerDialog
import java.util.UUID

private enum class DayRelation { ANY, SAME_DAY_AS, NOT_SAME_DAY_AS }

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
    calendarEvents: List<CalendarEvent> = emptyList(),
    availableBlocks: List<NamedBlock> = emptyList(),
    // Block-task mode: when set, the sheet saves a BlockTask instead of a floating TaskRequest.
    forBlock: String? = null,
    initialBlockTask: BlockTask? = null,
    onDismiss: () -> Unit,
    onSave: (TaskRequest) -> Unit = {},
    onSaveBlockTask: ((BlockTask) -> Unit)? = null
) {
    val isBlockMode = forBlock != null
    // ── Parse initial conditions ─────────────────────────────────────────────
    val initConditions = initial?.conditions ?: initialBlockTask?.conditions ?: emptyList()
    val initTw = initConditions.firstOrNull { it.type == "timeWindow" }

    // ── Form state ───────────────────────────────────────────────────────────
    var title by remember { mutableStateOf(initial?.title ?: initialBlockTask?.title ?: "") }
    var titleError by remember { mutableStateOf(false) }

    val initDuration = initial?.durationMinutes ?: initialBlockTask?.durationMinutes ?: 30
    var durationMinutes by remember { mutableIntStateOf(if (initDuration in DURATION_PRESETS) initDuration else 30) }
    var customDuration  by remember { mutableStateOf((initial != null || initialBlockTask != null) && initDuration !in DURATION_PRESETS) }
    var customDurText   by remember { mutableStateOf(if ((initial != null || initialBlockTask != null) && initDuration !in DURATION_PRESETS) initDuration.toString() else "") }
    var customDurError  by remember { mutableStateOf(false) }

    val initPriority = initial?.priority ?: initialBlockTask?.priority ?: 5
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

    var afterTaskIds by remember { mutableStateOf(
        initConditions.firstOrNull { it.type == "afterTask" }?.referenceTaskIds?.toSet() ?: emptySet()
    ) }
    var afterTime by remember { mutableStateOf<String?>(initTw?.start?.takeIf { it != "00:00" }) }
    var beforeTaskIds by remember { mutableStateOf(
        initConditions.firstOrNull { it.type == "beforeTask" }?.referenceTaskIds?.toSet() ?: emptySet()
    ) }
    var beforeTime by remember { mutableStateOf<String?>(initTw?.end?.takeIf { it != "23:59" }) }
    var showAfterTimePicker  by remember { mutableStateOf(false) }
    var showBeforeTimePicker by remember { mutableStateOf(false) }

    val initDays = initConditions.firstOrNull { it.type == "daysOfWeek" }?.days?.toSet() ?: emptySet()
    var selectedDays by remember { mutableStateOf(initDays) }

    // ── Calendar event condition state ───────────────────────────────────────
    var afterCalEventIds by remember { mutableStateOf(
        initConditions.filter { it.type == "afterCalEvent" }.mapNotNull { it.calendarEventId }.toSet()
    ) }
    var beforeCalEventIds by remember { mutableStateOf(
        initConditions.filter { it.type == "beforeCalEvent" }.mapNotNull { it.calendarEventId }.toSet()
    ) }
    var duringCalEventId by remember { mutableStateOf(
        initConditions.firstOrNull { it.type == "duringCalEvent" }?.calendarEventId
    ) }
    val todayCalEvents = remember(calendarEvents) {
        calendarEvents.filter { !it.allDay && !it.title.equals("sleep", ignoreCase = true) }
    }

    // ── Block anchor condition state (non-block-mode only) ───────────────────
    var afterBlockId by remember { mutableStateOf(
        initConditions.firstOrNull { it.type == "afterBlock" }?.blockId
    ) }
    var beforeBlockId by remember { mutableStateOf(
        initConditions.firstOrNull { it.type == "beforeBlock" }?.blockId
    ) }

    // ── Recurrence state ─────────────────────────────────────────────────────
    var taskRecurrenceRule by remember { mutableStateOf<RecurrenceRule?>(
        initConditions.firstOrNull { it.type in setOf("oneOff", "everyNDays", "everyNWeeks", "everyNMonths", "nTimesPerPeriod") }
            ?.toEventCondition()?.let { cond ->
                when (cond) {
                    is com.waypoint.app.planner.EventCondition.OneOff           -> RecurrenceRule.OneOff(cond.date)
                    is com.waypoint.app.planner.EventCondition.EveryNDays       -> RecurrenceRule.EveryNDays(cond.n, cond.anchorDate)
                    is com.waypoint.app.planner.EventCondition.EveryNWeeks      -> RecurrenceRule.EveryNWeeks(cond.n, cond.anchorDate)
                    is com.waypoint.app.planner.EventCondition.EveryNMonths     -> RecurrenceRule.EveryNMonths(cond.n, cond.anchorDate)
                    is com.waypoint.app.planner.EventCondition.NTimesPerPeriod  -> RecurrenceRule.NTimesPerPeriod(cond.count, cond.periodDays, cond.anchorDate)
                    else -> null
                }
            }
    ) }

    // ── Routine & buffer state ───────────────────────────────────────────────
    var isRoutine by remember { mutableStateOf(initial?.isRoutine ?: initialBlockTask?.isRoutine ?: false) }
    val subtasks = remember { mutableStateListOf<SubtaskDef>().also {
        it.addAll(initial?.subtasks ?: initialBlockTask?.subtasks ?: emptyList())
    } }
    var newSubtaskTitle by remember { mutableStateOf("") }
    var newSubtaskDurText by remember { mutableStateOf("15") }

    val bufferPresets = listOf(0, 5, 10, 15, 30)
    val bufferLabels  = listOf("None", "5m", "10m", "15m", "30m")
    val initBuffer = initial?.bufferMinutes ?: initialBlockTask?.bufferMinutes ?: 0
    var bufferMinutes by remember { mutableIntStateOf(if (initBuffer in bufferPresets) initBuffer else 0) }
    var customBuffer  by remember { mutableStateOf(initBuffer !in bufferPresets && initBuffer > 0) }
    var customBufText by remember { mutableStateOf(if (initBuffer !in bufferPresets && initBuffer > 0) initBuffer.toString() else "") }

    var useMeasuredDuration by remember { mutableStateOf(initial?.useMeasuredDuration ?: initialBlockTask?.useMeasuredDuration ?: false) }
    // Migrate old scheduleLate=true tasks that pre-date the zone field
    var zone by remember { mutableStateOf(
        initial?.zone ?: if (initial?.scheduleLate == true) PlannerZone.EVENING else null
    ) }

    // Color (both modes)
    var taskColor by remember { mutableStateOf(initial?.colorArgb ?: initialBlockTask?.colorArgb) }

    // Block-task-mode state
    var blockPlacement  by remember { mutableStateOf(initialBlockTask?.placement   ?: BlockTaskPlacement.DURING) }
    var blockSubPlace   by remember { mutableStateOf(initialBlockTask?.subPlacement) }
    var blockIsAlways   by remember { mutableStateOf(initialBlockTask?.isAlways    ?: true) }

    // ── Trigger chain state ──────────────────────────────────────────────────
    val triggers = remember { mutableStateListOf<TaskTrigger>().also {
        it.addAll(initial?.triggers ?: initialBlockTask?.triggers ?: emptyList())
    } }
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

    // Ordering graph derived from all tasks' declared constraints.
    // Used to detect impossible After/Before combinations in the UI.
    val orderGraph = remember(availableTasks) { buildOrderGraph(availableTasks) }

    // "After" chip X conflicts when any selected "Before" task Y is known to come before X —
    // meaning [after X, before Y] would be an empty window.
    fun isAfterConflicting(taskId: String): Boolean =
        beforeTaskIds.any { beforeId ->
            when {
                beforeId == TASK_REF_SLEEP -> false
                beforeId == taskId         -> true   // same task in both
                else -> isReachable(beforeId, taskId, orderGraph)
            }
        }

    // "Before" chip Y conflicts when any selected "After" task X is known to come after Y —
    // same impossible window, other direction.
    fun isBeforeConflicting(taskId: String): Boolean =
        afterTaskIds.any { afterId ->
            when {
                afterId == TASK_REF_SLEEP -> false
                afterId == taskId         -> true    // same task in both
                else -> isReachable(taskId, afterId, orderGraph)
            }
        }

    // ── Save logic ───────────────────────────────────────────────────────────
    fun save() {
        titleError    = title.trim().isEmpty()
        val resolvedDuration = if (customDuration) customDurText.toIntOrNull() ?: 0 else durationMinutes
        customDurError = customDuration && resolvedDuration <= 0
        if (titleError || customDurError) return
        val resolvedBuffer = if (customBuffer) customBufText.toIntOrNull() ?: 0 else bufferMinutes

        if (isBlockMode && forBlock != null && onSaveBlockTask != null) {
            val blockConditions = buildList {
                when (dayRelation) {
                    DayRelation.SAME_DAY_AS    -> if (dayRelationTaskIds.isNotEmpty())
                        add(TaskConditionSpec("sameDayAs", referenceTaskIds = dayRelationTaskIds.sorted()))
                    DayRelation.NOT_SAME_DAY_AS -> if (dayRelationTaskIds.isNotEmpty())
                        add(TaskConditionSpec("notSameDayAs", referenceTaskIds = dayRelationTaskIds.sorted()))
                    DayRelation.ANY            -> Unit
                }
                if (afterTaskIds.isNotEmpty())
                    add(TaskConditionSpec("afterTask", referenceTaskIds = afterTaskIds.sorted()))
                if (beforeTaskIds.isNotEmpty())
                    add(TaskConditionSpec("beforeTask", referenceTaskIds = beforeTaskIds.sorted()))
                if (afterTime != null || beforeTime != null)
                    add(TaskConditionSpec("timeWindow", start = afterTime ?: "00:00", end = beforeTime ?: "23:59"))
                if (selectedDays.isNotEmpty())
                    add(TaskConditionSpec("daysOfWeek", days = selectedDays.sorted()))
                afterCalEventIds.forEach { evtId -> add(TaskConditionSpec("afterCalEvent", calendarEventId = evtId)) }
                beforeCalEventIds.forEach { evtId -> add(TaskConditionSpec("beforeCalEvent", calendarEventId = evtId)) }
                duringCalEventId?.let { evtId -> add(TaskConditionSpec("duringCalEvent", calendarEventId = evtId)) }
                afterBlockId?.let { add(TaskConditionSpec("afterBlock", blockId = it)) }
                beforeBlockId?.let { add(TaskConditionSpec("beforeBlock", blockId = it)) }
                when (val r = taskRecurrenceRule) {
                    is RecurrenceRule.OneOff          -> add(TaskConditionSpec("oneOff", oneOffDate = r.date))
                    is RecurrenceRule.EveryNDays      -> add(TaskConditionSpec("everyNDays", intervalN = r.n, anchorDate = r.anchorDate))
                    is RecurrenceRule.EveryNWeeks     -> add(TaskConditionSpec("everyNWeeks", intervalN = r.n, anchorDate = r.anchorDate))
                    is RecurrenceRule.EveryNMonths    -> add(TaskConditionSpec("everyNMonths", intervalN = r.n, anchorDate = r.anchorDate))
                    is RecurrenceRule.NTimesPerPeriod -> add(TaskConditionSpec("nTimesPerPeriod", occurrenceCount = r.count, intervalN = r.periodDays, anchorDate = r.anchorDate))
                    else -> Unit
                }
            }
            onSaveBlockTask(
                BlockTask(
                    id                  = initialBlockTask?.id ?: UUID.randomUUID().toString(),
                    blockId             = forBlock,
                    title               = title.trim(),
                    durationMinutes     = resolvedDuration,
                    placement           = blockPlacement,
                    priority            = priority,
                    bufferMinutes       = resolvedBuffer,
                    isAlways            = blockIsAlways,
                    subPlacement        = if (blockPlacement == BlockTaskPlacement.DURING) blockSubPlace else null,
                    conditions          = blockConditions,
                    useMeasuredDuration = useMeasuredDuration,
                    triggers            = triggers.toList(),
                    isRoutine           = isRoutine,
                    subtasks            = subtasks.toList(),
                    colorArgb           = taskColor
                )
            )
            return
        }

        val conditions = buildList {
            when (dayRelation) {
                DayRelation.SAME_DAY_AS    -> if (dayRelationTaskIds.isNotEmpty())
                    add(TaskConditionSpec("sameDayAs", referenceTaskIds = dayRelationTaskIds.sorted()))
                DayRelation.NOT_SAME_DAY_AS -> if (dayRelationTaskIds.isNotEmpty())
                    add(TaskConditionSpec("notSameDayAs", referenceTaskIds = dayRelationTaskIds.sorted()))
                DayRelation.ANY            -> Unit
            }
            if (afterTaskIds.isNotEmpty())
                add(TaskConditionSpec("afterTask", referenceTaskIds = afterTaskIds.sorted()))
            if (beforeTaskIds.isNotEmpty())
                add(TaskConditionSpec("beforeTask", referenceTaskIds = beforeTaskIds.sorted()))
            if (afterTime != null || beforeTime != null) {
                add(TaskConditionSpec("timeWindow", start = afterTime ?: "00:00", end = beforeTime ?: "23:59"))
            }
            if (selectedDays.isNotEmpty()) {
                add(TaskConditionSpec("daysOfWeek", days = selectedDays.sorted()))
            }
            afterCalEventIds.forEach { evtId ->
                add(TaskConditionSpec("afterCalEvent", calendarEventId = evtId))
            }
            beforeCalEventIds.forEach { evtId ->
                add(TaskConditionSpec("beforeCalEvent", calendarEventId = evtId))
            }
            duringCalEventId?.let { evtId ->
                add(TaskConditionSpec("duringCalEvent", calendarEventId = evtId))
            }
            afterBlockId?.let { add(TaskConditionSpec("afterBlock", blockId = it)) }
            beforeBlockId?.let { add(TaskConditionSpec("beforeBlock", blockId = it)) }
            when (val r = taskRecurrenceRule) {
                is RecurrenceRule.OneOff       -> add(TaskConditionSpec("oneOff", oneOffDate = r.date))
                is RecurrenceRule.EveryNDays   -> add(TaskConditionSpec("everyNDays", intervalN = r.n, anchorDate = r.anchorDate))
                is RecurrenceRule.EveryNWeeks  -> add(TaskConditionSpec("everyNWeeks", intervalN = r.n, anchorDate = r.anchorDate))
                is RecurrenceRule.EveryNMonths -> add(TaskConditionSpec("everyNMonths", intervalN = r.n, anchorDate = r.anchorDate))
                else -> Unit
            }
        }

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
                triggers            = triggers.toList(),
                scheduleLate        = zone == PlannerZone.EVENING,
                zone                = zone,
                colorArgb           = taskColor
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
                        text = when {
                            isBlockMode && initialBlockTask == null -> "Add Block Task"
                            isBlockMode -> "Edit Block Task"
                            initial == null -> "Add Task"
                            else -> "Edit Task"
                        },
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

                    // Color
                    FormSection(title = "Color") {
                        val isCustomTaskColor = taskColor != null && taskColor !in BLOCK_COLORS
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // "None" swatch
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .then(if (taskColor == null)
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    else Modifier)
                                    .clickable { taskColor = null },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "—",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            BLOCK_COLORS.forEach { argb ->
                                Box(
                                    Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color(argb))
                                        .then(if (taskColor == argb)
                                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        else Modifier)
                                        .clickable { taskColor = argb }
                                )
                            }
                            // Custom swatch
                            val customSwatchBg = if (isCustomTaskColor) Color(taskColor!!)
                                                 else MaterialTheme.colorScheme.surfaceVariant
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(customSwatchBg)
                                    .then(if (isCustomTaskColor)
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    else Modifier)
                                    .clickable {
                                        if (!isCustomTaskColor) taskColor = 0xFF808080.toInt()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (!isCustomTaskColor) {
                                    Icon(
                                        Icons.Default.Add, contentDescription = "Custom color",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                        if (isCustomTaskColor && taskColor != null) {
                            val tc = taskColor!!

                            Spacer(Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                ColorPreviewSwatch(argb = tc, modifier = Modifier.size(40.dp))
                                Text(
                                    "#%08X".format(tc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            HsvColorPicker(
                                argb = tc,
                                onColorChange = { taskColor = it },
                                showAlpha = true
                            )
                        }
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

                    // Block mode: Placement (replaces Time of day)
                    if (isBlockMode) {
                        FormSection(title = "Placement") {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    FilterChip(
                                        selected = blockPlacement == BlockTaskPlacement.BEFORE,
                                        onClick  = { blockPlacement = BlockTaskPlacement.BEFORE; blockSubPlace = null },
                                        label    = { Text("Before block") }
                                    )
                                    FilterChip(
                                        selected = blockPlacement == BlockTaskPlacement.DURING,
                                        onClick  = { blockPlacement = BlockTaskPlacement.DURING },
                                        label    = { Text("During block") }
                                    )
                                    FilterChip(
                                        selected = blockPlacement == BlockTaskPlacement.AFTER,
                                        onClick  = { blockPlacement = BlockTaskPlacement.AFTER; blockSubPlace = null },
                                        label    = { Text("After block") }
                                    )
                                }
                                Text(
                                    text = when (blockPlacement) {
                                        BlockTaskPlacement.BEFORE -> "Scheduled in free time before the block starts"
                                        BlockTaskPlacement.DURING -> "Scheduled inside the block window"
                                        BlockTaskPlacement.AFTER  -> "Scheduled in free time after the block ends"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                )
                                // Sub-placement position: only shown for DURING tasks
                                if (blockPlacement == BlockTaskPlacement.DURING) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Position within block",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        FilterChip(
                                            selected = blockSubPlace == null,
                                            onClick  = { blockSubPlace = null },
                                            label    = { Text("Any") }
                                        )
                                        FilterChip(
                                            selected = blockSubPlace == BlockSubPlacement.START,
                                            onClick  = { blockSubPlace = BlockSubPlacement.START },
                                            label    = { Text("Start") }
                                        )
                                        FilterChip(
                                            selected = blockSubPlace == BlockSubPlacement.MID,
                                            onClick  = { blockSubPlace = BlockSubPlacement.MID },
                                            label    = { Text("Middle") }
                                        )
                                        FilterChip(
                                            selected = blockSubPlace == BlockSubPlacement.END,
                                            onClick  = { blockSubPlace = BlockSubPlacement.END },
                                            label    = { Text("End") }
                                        )
                                    }
                                    Text(
                                        text = when (blockSubPlace) {
                                            BlockSubPlacement.START -> "Placed first, at the top of the block"
                                            BlockSubPlacement.MID   -> "Placed in the middle third of the block"
                                            BlockSubPlacement.END   -> "Placed as late as possible within the block"
                                            null                    -> "No preference — fills wherever it fits"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                    )
                                }
                            }
                        }
                        FormSection(title = "Frequency") {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (blockIsAlways) "Every occurrence" else "Situational",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        if (blockIsAlways)
                                            "Scheduled automatically each time the block runs"
                                        else
                                            "Must be activated manually for each occurrence",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                                Switch(checked = blockIsAlways, onCheckedChange = { blockIsAlways = it })
                            }
                        }
                    }

                    // Time of day (floating tasks only)
                    if (!isBlockMode)
                    FormSection(title = "Time of day") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                FilterChip(
                                    selected = zone == null,
                                    onClick  = { zone = null },
                                    label    = { Text("Any") }
                                )
                                FilterChip(
                                    selected = zone == PlannerZone.MORNING,
                                    onClick  = { zone = PlannerZone.MORNING },
                                    label    = { Text("Morning") }
                                )
                                FilterChip(
                                    selected = zone == PlannerZone.AFTERNOON,
                                    onClick  = { zone = PlannerZone.AFTERNOON },
                                    label    = { Text("Afternoon") }
                                )
                                FilterChip(
                                    selected = zone == PlannerZone.EVENING,
                                    onClick  = { zone = PlannerZone.EVENING },
                                    label    = { Text("Evening") }
                                )
                            }
                            Text(
                                text = when (zone) {
                                    PlannerZone.MORNING   -> "Scheduled first thing after wake"
                                    PlannerZone.AFTERNOON -> "Scheduled in the middle third of your day"
                                    PlannerZone.EVENING   -> "Scheduled as late as possible before sleep"
                                    null                  -> "No preference — filled in wherever it fits"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            )
                        }
                    }

                    // Constraints — tag-based: active constraints shown as dismissible chips,
                    // new constraints added via a two-level inline picker.
                    FormSection(title = "Constraints") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

                            // null = picker closed; "categories" = category list; else = specific category
                            var constraintPicker by remember { mutableStateOf<String?>(null) }

                            // ── Active constraint tags ──────────────────────────────────────
                            val hasConstraints = selectedDays.isNotEmpty() ||
                                afterTime != null || beforeTime != null ||
                                afterTaskIds.isNotEmpty() || beforeTaskIds.isNotEmpty() ||
                                afterCalEventIds.isNotEmpty() || beforeCalEventIds.isNotEmpty() ||
                                duringCalEventId != null || taskRecurrenceRule != null ||
                                (!isBlockMode && (afterBlockId != null || beforeBlockId != null ||
                                    (dayRelation != DayRelation.ANY && dayRelationTaskIds.isNotEmpty())))

                            if (hasConstraints) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement   = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (selectedDays.isNotEmpty()) {
                                        val dayLabel = if (selectedDays.size <= 3)
                                            selectedDays.sorted().joinToString(" ") { DAY_NAMES[it - 1] }
                                        else "${selectedDays.size} days"
                                        ConstraintTag(dayLabel) { selectedDays = emptySet() }
                                    }
                                    if (afterTime != null)
                                        ConstraintTag("After $afterTime") { afterTime = null }
                                    if (beforeTime != null)
                                        ConstraintTag("Before $beforeTime") { beforeTime = null }
                                    afterTaskIds.forEach { tid ->
                                        val name = if (tid == TASK_REF_SLEEP) "Sleep"
                                                   else chainTargets.find { it.id == tid }?.title
                                                       ?: availableTasks.find { it.id == tid }?.title
                                                       ?: "Deleted task"
                                        ConstraintTag("After $name") { afterTaskIds = afterTaskIds - tid }
                                    }
                                    beforeTaskIds.forEach { tid ->
                                        val name = if (tid == TASK_REF_SLEEP) "Sleep"
                                                   else chainTargets.find { it.id == tid }?.title
                                                       ?: availableTasks.find { it.id == tid }?.title
                                                       ?: "Deleted task"
                                        ConstraintTag("Before $name") { beforeTaskIds = beforeTaskIds - tid }
                                    }
                                    afterCalEventIds.forEach { evtId ->
                                        val name = todayCalEvents.find { it.eventId == evtId }?.title ?: "Calendar event"
                                        ConstraintTag("After $name") { afterCalEventIds = afterCalEventIds - evtId }
                                    }
                                    beforeCalEventIds.forEach { evtId ->
                                        val name = todayCalEvents.find { it.eventId == evtId }?.title ?: "Calendar event"
                                        ConstraintTag("Before $name") { beforeCalEventIds = beforeCalEventIds - evtId }
                                    }
                                    duringCalEventId?.let { evtId ->
                                        val name = todayCalEvents.find { it.eventId == evtId }?.title ?: "Calendar event"
                                        ConstraintTag("During $name") { duringCalEventId = null }
                                    }
                                    taskRecurrenceRule?.let { r ->
                                        val label = when (r) {
                                            is RecurrenceRule.OneOff          -> "Once (${r.date})"
                                            is RecurrenceRule.EveryNDays      -> if (r.n == 1) "Every day" else "Every ${r.n} days"
                                            is RecurrenceRule.EveryNWeeks     -> if (r.n == 1) "Every week" else "Every ${r.n} weeks"
                                            is RecurrenceRule.EveryNMonths    -> if (r.n == 1) "Every month" else "Every ${r.n} months"
                                            is RecurrenceRule.NTimesPerPeriod -> if (r.count == 1) "Once every ${r.periodDays} days" else "${r.count}× / ${r.periodDays} days"
                                            is RecurrenceRule.DaysOfWeek      -> "Days of week"
                                        }
                                        ConstraintTag(label) { taskRecurrenceRule = null }
                                    }
                                    if (!isBlockMode) {
                                        afterBlockId?.let { bId ->
                                            val name = availableBlocks.find { it.id == bId }?.name ?: "Deleted block"
                                            ConstraintTag("After $name") { afterBlockId = null }
                                        }
                                        beforeBlockId?.let { bId ->
                                            val name = availableBlocks.find { it.id == bId }?.name ?: "Deleted block"
                                            ConstraintTag("Before $name") { beforeBlockId = null }
                                        }
                                        if (dayRelation != DayRelation.ANY) {
                                            dayRelationTaskIds.forEach { tid ->
                                                val name = chainTargets.find { it.id == tid }?.title
                                                    ?: availableTasks.find { it.id == tid }?.title
                                                    ?: "Deleted task"
                                                val prefix = if (dayRelation == DayRelation.SAME_DAY_AS) "Same day as" else "Not with"
                                                ConstraintTag("$prefix $name") {
                                                    val next = dayRelationTaskIds - tid
                                                    dayRelationTaskIds = next
                                                    if (next.isEmpty()) dayRelation = DayRelation.ANY
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // ── Add constraint entry / inline picker ────────────────────────
                            if (constraintPicker == null) {
                                FilterChip(
                                    selected = false,
                                    onClick  = { constraintPicker = "categories" },
                                    label    = { Text("+ Add constraint") }
                                )
                            } else {
                                Surface(
                                    tonalElevation = 2.dp,
                                    shape    = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        // Picker header
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment     = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = when (constraintPicker) {
                                                    "categories"  -> "Add constraint"
                                                    "daysOfWeek"  -> "Days of week"
                                                    "recurrence"  -> "Recurrence"
                                                    "after"       -> "After"
                                                    "before"      -> "Before"
                                                    "sameDayAs"   -> "Day relation"
                                                    "duringEvent" -> "During event"
                                                    else          -> "Add constraint"
                                                },
                                                style      = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (constraintPicker != "categories") {
                                                    TextButton(
                                                        onClick  = { constraintPicker = "categories" },
                                                        modifier = Modifier.height(24.dp).padding(horizontal = 0.dp)
                                                    ) {
                                                        Text("Back", style = MaterialTheme.typography.labelSmall)
                                                    }
                                                    Spacer(Modifier.width(4.dp))
                                                }
                                                IconButton(
                                                    onClick  = { constraintPicker = null },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }

                                        when (constraintPicker) {
                                            "categories" -> FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement   = Arrangement.spacedBy(4.dp)
                                            ) {
                                                FilterChip(
                                                    selected = false,
                                                    onClick  = { constraintPicker = "daysOfWeek" },
                                                    label    = { Text("Days of week") }
                                                )
                                                FilterChip(
                                                    selected = false,
                                                    onClick  = { constraintPicker = "recurrence" },
                                                    label    = { Text("Recurrence") }
                                                )
                                                FilterChip(
                                                    selected = false,
                                                    onClick  = { constraintPicker = "after" },
                                                    label    = { Text("After") }
                                                )
                                                FilterChip(
                                                    selected = false,
                                                    onClick  = { constraintPicker = "before" },
                                                    label    = { Text("Before") }
                                                )
                                                if (!isBlockMode) {
                                                    FilterChip(
                                                        selected = false,
                                                        onClick  = { constraintPicker = "sameDayAs" },
                                                        label    = { Text("Same day as / Not with") }
                                                    )
                                                    if (todayCalEvents.isNotEmpty()) FilterChip(
                                                        selected = false,
                                                        onClick  = { constraintPicker = "duringEvent" },
                                                        label    = { Text("During event") }
                                                    )
                                                }
                                            }

                                            "recurrence" -> RecurrencePicker(
                                                value             = taskRecurrenceRule,
                                                onChange          = { taskRecurrenceRule = it; constraintPicker = null },
                                                includeDaysOfWeek = false
                                            )

                                            "daysOfWeek" -> FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement   = Arrangement.spacedBy(4.dp)
                                            ) {
                                                DAY_NAMES.forEachIndexed { i, name ->
                                                    val day = i + 1
                                                    FilterChip(
                                                        selected = day in selectedDays,
                                                        onClick  = {
                                                            selectedDays = if (day in selectedDays)
                                                                selectedDays - day else selectedDays + day
                                                        },
                                                        label    = { Text(name) }
                                                    )
                                                }
                                            }

                                            "after" -> FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement   = Arrangement.spacedBy(4.dp)
                                            ) {
                                                if (afterTime != null) {
                                                    Row(
                                                        verticalAlignment     = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                                    ) {
                                                        TimePickerChip(value = afterTime!!, onValueChange = { afterTime = it })
                                                        IconButton(onClick = { afterTime = null }, modifier = Modifier.size(20.dp)) {
                                                            Icon(Icons.Default.Close, null, modifier = Modifier.size(12.dp),
                                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                                        }
                                                    }
                                                } else {
                                                    FilterChip(
                                                        selected = false,
                                                        onClick  = { showAfterTimePicker = true },
                                                        label    = { Text("+ Time") }
                                                    )
                                                }
                                                if (!isBlockMode) FilterChip(
                                                    selected = TASK_REF_SLEEP in afterTaskIds,
                                                    onClick  = {
                                                        afterTaskIds = if (TASK_REF_SLEEP in afterTaskIds)
                                                            afterTaskIds - TASK_REF_SLEEP else afterTaskIds + TASK_REF_SLEEP
                                                    },
                                                    label    = { Text("Sleep") }
                                                )
                                                chainTargets.forEach { task ->
                                                    val conflict = isAfterConflicting(task.id)
                                                    FilterChip(
                                                        selected = task.id in afterTaskIds,
                                                        enabled  = !conflict || task.id in afterTaskIds,
                                                        onClick  = {
                                                            afterTaskIds = if (task.id in afterTaskIds)
                                                                afterTaskIds - task.id else afterTaskIds + task.id
                                                        },
                                                        colors   = if (conflict && task.id !in afterTaskIds)
                                                            FilterChipDefaults.filterChipColors(
                                                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                                                                labelColor     = MaterialTheme.colorScheme.error
                                                            ) else FilterChipDefaults.filterChipColors(),
                                                        label    = { Text(task.title, style = MaterialTheme.typography.labelSmall) }
                                                    )
                                                }
                                                todayCalEvents.forEach { evt ->
                                                    FilterChip(
                                                        selected = evt.eventId in afterCalEventIds,
                                                        onClick  = {
                                                            afterCalEventIds = if (evt.eventId in afterCalEventIds)
                                                                afterCalEventIds - evt.eventId else afterCalEventIds + evt.eventId
                                                        },
                                                        label    = { Text(evt.title, style = MaterialTheme.typography.labelSmall) }
                                                    )
                                                }
                                                if (!isBlockMode) availableBlocks.forEach { block ->
                                                    FilterChip(
                                                        selected = afterBlockId == block.id,
                                                        onClick  = { afterBlockId = if (afterBlockId == block.id) null else block.id },
                                                        label    = { Text(block.name, style = MaterialTheme.typography.labelSmall) }
                                                    )
                                                }
                                            }

                                            "before" -> FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement   = Arrangement.spacedBy(4.dp)
                                            ) {
                                                if (beforeTime != null) {
                                                    Row(
                                                        verticalAlignment     = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                                    ) {
                                                        TimePickerChip(value = beforeTime!!, onValueChange = { beforeTime = it })
                                                        IconButton(onClick = { beforeTime = null }, modifier = Modifier.size(20.dp)) {
                                                            Icon(Icons.Default.Close, null, modifier = Modifier.size(12.dp),
                                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                                        }
                                                    }
                                                } else {
                                                    FilterChip(
                                                        selected = false,
                                                        onClick  = { showBeforeTimePicker = true },
                                                        label    = { Text("+ Time") }
                                                    )
                                                }
                                                if (!isBlockMode) FilterChip(
                                                    selected = TASK_REF_SLEEP in beforeTaskIds,
                                                    onClick  = {
                                                        beforeTaskIds = if (TASK_REF_SLEEP in beforeTaskIds)
                                                            beforeTaskIds - TASK_REF_SLEEP else beforeTaskIds + TASK_REF_SLEEP
                                                    },
                                                    label    = { Text("Sleep") }
                                                )
                                                chainTargets.forEach { task ->
                                                    val conflict = isBeforeConflicting(task.id)
                                                    FilterChip(
                                                        selected = task.id in beforeTaskIds,
                                                        enabled  = !conflict || task.id in beforeTaskIds,
                                                        onClick  = {
                                                            beforeTaskIds = if (task.id in beforeTaskIds)
                                                                beforeTaskIds - task.id else beforeTaskIds + task.id
                                                        },
                                                        colors   = if (conflict && task.id !in beforeTaskIds)
                                                            FilterChipDefaults.filterChipColors(
                                                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                                                                labelColor     = MaterialTheme.colorScheme.error
                                                            ) else FilterChipDefaults.filterChipColors(),
                                                        label    = { Text(task.title, style = MaterialTheme.typography.labelSmall) }
                                                    )
                                                }
                                                todayCalEvents.forEach { evt ->
                                                    FilterChip(
                                                        selected = evt.eventId in beforeCalEventIds,
                                                        onClick  = {
                                                            beforeCalEventIds = if (evt.eventId in beforeCalEventIds)
                                                                beforeCalEventIds - evt.eventId else beforeCalEventIds + evt.eventId
                                                        },
                                                        label    = { Text(evt.title, style = MaterialTheme.typography.labelSmall) }
                                                    )
                                                }
                                                if (!isBlockMode) availableBlocks.forEach { block ->
                                                    FilterChip(
                                                        selected = beforeBlockId == block.id,
                                                        onClick  = { beforeBlockId = if (beforeBlockId == block.id) null else block.id },
                                                        label    = { Text(block.name, style = MaterialTheme.typography.labelSmall) }
                                                    )
                                                }
                                            }

                                            "sameDayAs" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                FlowRow(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalArrangement   = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    FilterChip(
                                                        selected = dayRelation == DayRelation.SAME_DAY_AS,
                                                        onClick  = { dayRelation = DayRelation.SAME_DAY_AS },
                                                        label    = { Text("Same day as") }
                                                    )
                                                    FilterChip(
                                                        selected = dayRelation == DayRelation.NOT_SAME_DAY_AS,
                                                        onClick  = {
                                                            dayRelation = DayRelation.NOT_SAME_DAY_AS
                                                            dayRelationTaskIds = emptySet()
                                                        },
                                                        label    = { Text("Not with") }
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
                                                            verticalArrangement   = Arrangement.spacedBy(4.dp)
                                                        ) {
                                                            chainTargets.forEach { task ->
                                                                FilterChip(
                                                                    selected = task.id in dayRelationTaskIds,
                                                                    onClick  = {
                                                                        dayRelationTaskIds = if (task.id in dayRelationTaskIds)
                                                                            dayRelationTaskIds - task.id else dayRelationTaskIds + task.id
                                                                    },
                                                                    label    = { Text(task.title, style = MaterialTheme.typography.labelSmall) }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            "duringEvent" -> FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement   = Arrangement.spacedBy(4.dp)
                                            ) {
                                                todayCalEvents.forEach { evt ->
                                                    FilterChip(
                                                        selected = duringCalEventId == evt.eventId,
                                                        onClick  = {
                                                            duringCalEventId = if (duringCalEventId == evt.eventId) null else evt.eventId
                                                        },
                                                        label    = { Text(evt.title, style = MaterialTheme.typography.labelSmall) }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Time picker dialogs (shown on demand from the After/Before sub-pickers)
                            if (showAfterTimePicker) {
                                TimePickerDialog(
                                    initialHour   = afterTime?.split(":")?.getOrNull(0)?.toIntOrNull() ?: 9,
                                    initialMinute = afterTime?.split(":")?.getOrNull(1)?.toIntOrNull() ?: 0,
                                    onDismiss = { showAfterTimePicker = false },
                                    onConfirm = { h, m ->
                                        afterTime = "%02d:%02d".format(h, m)
                                        showAfterTimePicker = false
                                    }
                                )
                            }
                            if (showBeforeTimePicker) {
                                TimePickerDialog(
                                    initialHour   = beforeTime?.split(":")?.getOrNull(0)?.toIntOrNull() ?: 22,
                                    initialMinute = beforeTime?.split(":")?.getOrNull(1)?.toIntOrNull() ?: 0,
                                    onDismiss = { showBeforeTimePicker = false },
                                    onConfirm = { h, m ->
                                        beforeTime = "%02d:%02d".format(h, m)
                                        showBeforeTimePicker = false
                                    }
                                )
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
                            Column(Modifier.weight(1f)) {
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
                                        ?: "Deleted task"
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

/**
 * Builds a "comes before" adjacency map from the declared constraints of all tasks.
 * Edge A→B means A is definitively scheduled before B.
 */
private fun buildOrderGraph(tasks: List<TaskRequest>): Map<String, Set<String>> {
    val graph = mutableMapOf<String, MutableSet<String>>()
    for (task in tasks) {
        for (cond in task.conditions) when (cond.type) {
            "beforeTask" -> cond.referenceTaskIds?.forEach { targetId ->
                if (targetId != TASK_REF_SLEEP)
                    graph.getOrPut(task.id) { mutableSetOf() } += targetId
            }
            "afterTask" -> cond.referenceTaskIds?.forEach { depId ->
                if (depId != TASK_REF_SLEEP)
                    graph.getOrPut(depId) { mutableSetOf() } += task.id
            }
        }
    }
    return graph
}

/** BFS reachability: true if [to] is reachable from [from] via "comes before" edges. */
private fun isReachable(from: String, to: String, graph: Map<String, Set<String>>): Boolean {
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque(graph[from]?.toList() ?: emptyList())
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        if (cur == to) return true
        if (visited.add(cur)) graph[cur]?.forEach { if (it !in visited) queue.add(it) }
    }
    return false
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

@Composable
private fun ConstraintTag(label: String, onRemove: () -> Unit) {
    FilterChip(
        selected     = true,
        onClick      = onRemove,
        label        = { Text(label, style = MaterialTheme.typography.labelSmall) },
        trailingIcon = {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove",
                modifier           = Modifier.size(14.dp)
            )
        }
    )
}
