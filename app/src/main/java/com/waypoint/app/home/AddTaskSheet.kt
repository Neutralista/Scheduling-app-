package com.waypoint.app.home

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.LaunchedEffect
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
import com.waypoint.app.planner.BlockPhase
import com.waypoint.app.planner.BlockTask
import com.waypoint.app.planner.BlockTaskPlacement
import com.waypoint.app.planner.EventPlannerRegistry
import com.waypoint.app.planner.NamedBlock
import com.waypoint.app.planner.NamedBlockStore
import com.waypoint.app.planner.PlannerEvent
import com.waypoint.app.planner.PlannerZone
import com.waypoint.app.planner.RecurrenceRule
import com.waypoint.app.planner.TASK_REF_SLEEP
import com.waypoint.app.planner.TaskConditionSpec
import com.waypoint.app.planner.TaskRequest
import com.waypoint.app.planner.oneOffDate
import com.waypoint.app.planner.TaskTrigger
import com.waypoint.app.planner.TriggerEvent
import com.waypoint.app.signal.CalendarEvent
import com.waypoint.app.ui.components.ColorPreviewSwatch
import com.waypoint.app.ui.components.HsvColorPicker
import com.waypoint.app.ui.components.ConstraintTagBar
import com.waypoint.app.planner.ConstraintTags
import com.waypoint.app.ui.components.TimePickerChip
import com.waypoint.app.ui.components.TimePickerDialog
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Calendar
import java.util.UUID
import kotlinx.coroutines.delay


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

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AddTaskSheet(
    initial: TaskRequest? = null,
    /** The day a new task is for (a free slot's day); a new task happens once, on it. */
    defaultDate: LocalDate? = null,
    availableTasks: List<TaskRequest> = emptyList(),
    calendarEvents: List<CalendarEvent> = emptyList(),
    availableBlocks: List<NamedBlock> = emptyList(),
    // Block-task mode: when set, the sheet saves a BlockTask instead of a floating TaskRequest.
    forBlock: String? = null,
    initialBlockTask: BlockTask? = null,
    // The block's phases, so a DURING task can be put in one.
    blockPhases: List<BlockPhase> = emptyList(),
    // Optional — when both are provided (floating-task mode only), a live "when will this
    // actually land" preview is shown, computed against a throwaway copy of today's real plan.
    eventPlanner: EventPlannerRegistry? = null,
    namedBlockStore: NamedBlockStore? = null,
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

    // Every tag but time of day (days, repeats, after/before, same day, not with, during).
    var tags by remember { mutableStateOf(ConstraintTags.fromSpecs(initConditions)) }

    // Once · Repeats (floating tasks): a new task happens once, on [defaultDate] or today; an
    // existing one keeps what it was. Repeats means every day unless Days/Repeats tags say otherwise.
    val initOneOff = (tags.recurrence as? RecurrenceRule.OneOff)?.date
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    var once by remember { mutableStateOf(initial == null || initOneOff != null) }
    var onceDate by remember { mutableStateOf(initOneOff ?: defaultDate ?: LocalDate.now()) }
    var showOnceDatePicker by remember { mutableStateOf(false) }

    var afterTime by remember { mutableStateOf<String?>(initTw?.start?.takeIf { it != "00:00" }) }
    var beforeTime by remember { mutableStateOf<String?>(initTw?.end?.takeIf { it != "23:59" }) }
    var showAfterTimePicker  by remember { mutableStateOf(false) }
    var showBeforeTimePicker by remember { mutableStateOf(false) }

    // Lives in the "Time of day" section as one unified, mutually-exclusive "when" control:
    // "zone" = a loose Morning/Afternoon/Evening preference (floating tasks only — block tasks
    // use "Position within block" instead, scoped to the block's own window rather than the
    // whole day); "anchor" = a soft target time that drifts within a flex range if the exact
    // slot is busy; "window" = a hard two-sided range,
    // UI sugar over the same After/Before pair used standalone in Constraints (a combined
    // start+end picker framed as a range) — placement within it already scatters by priority
    // since the scheduler's forward-fit path jitters any task, constrained or not. Switching
    // modes clears whichever of zone/aroundTime/afterTime+beforeTime isn't in use.
    val initAround = initConditions.firstOrNull { it.type == "aroundTime" }
    var aroundTime by remember { mutableStateOf(initAround?.start) }
    var aroundFlexMinutes by remember { mutableIntStateOf(initAround?.flexMinutes ?: 60) }
    var showAroundTimePicker by remember { mutableStateOf(false) }
    var aroundMode by remember { mutableStateOf(
        when {
            initAround != null -> "anchor"
            initTw != null     -> "window"
            else                -> "zone"
        }
    ) }

    val todayCalEvents = remember(calendarEvents) {
        calendarEvents.filter { !it.allDay && !it.title.equals("sleep", ignoreCase = true) }
    }

    // ── Buffer state ─────────────────────────────────────────────────────────
    // Routines (steps with timers) are gone: they're auto-placed blocks now (RoutineMigration).

    val bufferPresets = listOf(0, 5, 10, 15, 30)
    val bufferLabels  = listOf("None", "5m", "10m", "15m", "30m")
    val initBuffer = initial?.bufferMinutes ?: initialBlockTask?.bufferMinutes ?: 0
    var bufferMinutes by remember { mutableIntStateOf(if (initBuffer in bufferPresets) initBuffer else 0) }
    var customBuffer  by remember { mutableStateOf(initBuffer !in bufferPresets && initBuffer > 0) }
    var customBufText by remember { mutableStateOf(if (initBuffer !in bufferPresets && initBuffer > 0) initBuffer.toString() else "") }

    var remindAtStart by remember { mutableStateOf(initial?.remindAtStart ?: false) }
    var useMeasuredDuration by remember { mutableStateOf(initial?.useMeasuredDuration ?: initialBlockTask?.useMeasuredDuration ?: false) }
    // Migrate old scheduleLate=true tasks that pre-date the zone field
    var zone by remember { mutableStateOf(
        initial?.zone ?: if (initial?.scheduleLate == true) PlannerZone.EVENING else null
    ) }

    // Color (both modes). Task/block accent colors are solid identity colors, never meant to
    // carry partial alpha — normalize any pre-existing bad value (e.g. from before showAlpha
    // was disabled below) so editing an old task repairs its color rather than re-showing it.
    var taskColor by remember { mutableStateOf((initial?.colorArgb ?: initialBlockTask?.colorArgb)?.let { it or 0xFF000000.toInt() }) }

    // Block-task-mode state
    var blockPlacement  by remember { mutableStateOf(initialBlockTask?.placement   ?: BlockTaskPlacement.DURING) }
    var blockSubPlace   by remember { mutableStateOf(initialBlockTask?.subPlacement) }
    var blockIsAlways   by remember { mutableStateOf(initialBlockTask?.isAlways    ?: true) }
    // Sequenced: runs in a fixed order relative to other sequenced DURING tasks in this block.
    // Real numbering happens where sibling tasks are visible (NamedBlockSheet/BlocksTab save
    // callbacks) — -1 here is a sentinel meaning "new, not yet numbered".
    var blockSequenced  by remember { mutableStateOf(initialBlockTask?.sequence != null) }
    var blockPhaseId    by remember { mutableStateOf(initialBlockTask?.phaseId?.takeIf { id -> blockPhases.any { it.id == id } }) }

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
        tags.beforeTaskIds.any { beforeId ->
            when {
                beforeId == TASK_REF_SLEEP -> false
                beforeId == taskId         -> true   // same task in both
                else -> isReachable(beforeId, taskId, orderGraph)
            }
        }

    // "Before" chip Y conflicts when any selected "After" task X is known to come after Y —
    // same impossible window, other direction.
    fun isBeforeConflicting(taskId: String): Boolean =
        tags.afterTaskIds.any { afterId ->
            when {
                afterId == TASK_REF_SLEEP -> false
                afterId == taskId         -> true    // same task in both
                else -> isReachable(taskId, afterId, orderGraph)
            }
        }

    // Shared between save() and the live scheduling preview below, so the two can never drift.
    fun buildFloatingConditions(): List<TaskConditionSpec> = buildList {
        if (aroundTime != null) {
            add(TaskConditionSpec("aroundTime", start = aroundTime, flexMinutes = aroundFlexMinutes))
        } else if (afterTime != null || beforeTime != null) {
            add(TaskConditionSpec("timeWindow", start = afterTime ?: "00:00", end = beforeTime ?: "23:59"))
        }
        val t = when {
            isBlockMode -> tags
            once -> tags.copy(days = emptySet(), recurrence = RecurrenceRule.OneOff(onceDate.toString()))
            tags.recurrence is RecurrenceRule.OneOff -> tags.copy(recurrence = null)
            else -> tags
        }
        addAll(t.toSpecs())
    }

    // ── Save logic ───────────────────────────────────────────────────────────
    fun save() {
        titleError    = title.trim().isEmpty()
        val resolvedDuration = if (customDuration) customDurText.toIntOrNull() ?: 0 else durationMinutes
        customDurError = customDuration && resolvedDuration <= 0
        if (titleError || customDurError) return
        val resolvedBuffer = if (customBuffer) customBufText.toIntOrNull() ?: 0 else bufferMinutes

        if (isBlockMode && forBlock != null && onSaveBlockTask != null) {
            val blockConditions = buildFloatingConditions()
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
                    // Moved to another phase: renumbered at the end of that phase's order.
                    sequence            = if (blockPlacement == BlockTaskPlacement.DURING && blockSequenced)
                                              initialBlockTask?.let { t -> t.sequence?.takeIf { blockPhaseId == t.phaseId } } ?: -1
                                          else null,
                    phaseId             = if (blockPlacement == BlockTaskPlacement.DURING) blockPhaseId else null,
                    conditions          = blockConditions,
                    useMeasuredDuration = useMeasuredDuration,
                    triggers            = triggers.toList(),
                    colorArgb           = taskColor
                )
            )
            return
        }

        val conditions = buildFloatingConditions()

        onSave(
            TaskRequest(
                id                  = initial?.id ?: UUID.randomUUID().toString(),
                title               = title.trim(),
                durationMinutes     = resolvedDuration,
                priority            = priority,
                sourceScriptId      = initial?.sourceScriptId ?: "user",
                conditions          = conditions,
                bufferMinutes       = resolvedBuffer,
                useMeasuredDuration = useMeasuredDuration,
                remindAtStart       = remindAtStart,
                triggers            = triggers.toList(),
                scheduleLate        = aroundMode == "zone" && zone == PlannerZone.EVENING,
                zone                = if (aroundMode == "zone") zone else null,
                colorArgb           = taskColor,
                // Still done if it's the same one-off; moved to another date, it's to do again.
                completedOn         = initial?.let { prev ->
                    prev.completedOn?.takeIf {
                        conditions.oneOffDate() != null && conditions.oneOffDate() == prev.conditions.oneOffDate()
                    }
                }
            )
        )
    }

    // ── Live scheduling preview (floating tasks only) ───────────────────────
    // Runs against a throwaway EventPlannerRegistry seeded from a snapshot of today's real
    // events (minus this task's own current placement, if editing) plus the in-progress draft
    // — never mutates the shared registry, so nothing else in the app ever sees an unsaved
    // edit. Best-effort: work-shift bounds aren't available here, so a DuringShift-constrained
    // task may preview slightly differently than it will actually place.
    var previewText by remember { mutableStateOf<String?>(null) }
    val previewTaskId = remember { initial?.id ?: UUID.randomUUID().toString() }
    if (!isBlockMode && eventPlanner != null && namedBlockStore != null) {
        LaunchedEffect(Unit) {
            while (true) {
                val resolvedDur = if (customDuration) customDurText.toIntOrNull() ?: 0 else durationMinutes
                if (title.isNotBlank() && resolvedDur > 0) {
                    val resolvedBuf = if (customBuffer) customBufText.toIntOrNull() ?: 0 else bufferMinutes
                    val candidate = PlannerEvent(
                        id = previewTaskId,
                        title = title.trim(),
                        durationMinutes = resolvedDur,
                        priority = priority,
                        conditions = buildFloatingConditions().mapNotNull { it.toEventCondition() },
                        bufferMinutes = resolvedBuf,
                        scheduleLate = aroundMode == "zone" && zone == PlannerZone.EVENING,
                        zone = if (aroundMode == "zone") zone else null
                    )
                    val today = LocalDate.now()
                    val preview = EventPlannerRegistry()
                    eventPlanner.events.forEach { if (it.id != previewTaskId) preview.register(it) }
                    preview.register(candidate)
                    val calBlocks = calendarEvents
                        .filter { !it.allDay && !it.title.equals("sleep", ignoreCase = true) }
                        .associate { it.eventId to (it.startMillis to it.endMillis) }
                    val plan = preview.planForDate(
                        today,
                        calendarEventBlocks = calBlocks,
                        reservingBlocks = calBlocks.values.toList(),
                        namedBlockInstances = namedBlockStore.resolveFixedInstancesForDate(today),
                        floatingBlocks = namedBlockStore.resolveFloatingInstancesForDate(today),
                        nowMs = System.currentTimeMillis()
                    )
                    val landed = plan.scheduled.find { it.event.id == previewTaskId }
                    val rejected = plan.blocked.find { it.event.id == previewTaskId }
                    previewText = when {
                        landed != null -> "≈ lands ${fmtPreviewTime(landed.startMillis)} – ${fmtPreviewTime(landed.endMillis)} today"
                        rejected != null -> "Can't be scheduled today — ${rejected.reason}"
                        else -> null
                    }
                } else {
                    previewText = null
                }
                delay(600)
            }
        }
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

                if (previewText != null) {
                    Text(
                        text = previewText!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (previewText!!.startsWith("Can't"))
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                }

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

                    // Once · Repeats — most quick tasks happen once, so that's the default.
                    if (!isBlockMode) FormSection(title = "Happens") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = once, onClick = { once = true }, label = { Text("Once") })
                                FilterChip(selected = !once, onClick = { once = false }, label = { Text("Repeats") })
                            }
                            if (once) {
                                val today = LocalDate.now()
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    FilterChip(selected = onceDate == today, onClick = { onceDate = today }, label = { Text("Today") })
                                    FilterChip(
                                        selected = onceDate == today.plusDays(1),
                                        onClick = { onceDate = today.plusDays(1) },
                                        label = { Text("Tomorrow") }
                                    )
                                    val other = onceDate != today && onceDate != today.plusDays(1)
                                    FilterChip(
                                        selected = other,
                                        onClick = { showOnceDatePicker = true },
                                        label = {
                                            Text(
                                                if (other) onceDate.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
                                                else "Pick a date"
                                            )
                                        }
                                    )
                                }
                                Text(
                                    "Not done by then, it carries over each day until it is; once done, it's cleared the next day.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            } else {
                                Text(
                                    "Every day, unless you add a Days or Repeats tag below.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                    if (showOnceDatePicker) {
                        val dpState = androidx.compose.material3.rememberDatePickerState(
                            initialSelectedDateMillis = onceDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                        )
                        androidx.compose.material3.DatePickerDialog(
                            onDismissRequest = { showOnceDatePicker = false },
                            confirmButton = {
                                TextButton(onClick = {
                                    dpState.selectedDateMillis?.let { ms ->
                                        onceDate = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                                    }
                                    showOnceDatePicker = false
                                }) { Text("OK") }
                            },
                            dismissButton = { TextButton(onClick = { showOnceDatePicker = false }) { Text("Cancel") } }
                        ) { androidx.compose.material3.DatePicker(state = dpState) }
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
                                onColorChange = { taskColor = it or 0xFF000000.toInt() },
                                showAlpha = false
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

                    // Block mode: Placement — where within/around the block this task sits.
                    // Time of day (below) still applies on top of this for a precise anchor.
                    if (isBlockMode) {
                        FormSection(title = "Placement") {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    FilterChip(
                                        selected = blockPlacement == BlockTaskPlacement.BEFORE,
                                        onClick  = { blockPlacement = BlockTaskPlacement.BEFORE; blockSubPlace = null; blockSequenced = false },
                                        label    = { Text("Before block") }
                                    )
                                    FilterChip(
                                        selected = blockPlacement == BlockTaskPlacement.DURING,
                                        onClick  = { blockPlacement = BlockTaskPlacement.DURING },
                                        label    = { Text("During block") }
                                    )
                                    FilterChip(
                                        selected = blockPlacement == BlockTaskPlacement.AFTER,
                                        onClick  = { blockPlacement = BlockTaskPlacement.AFTER; blockSubPlace = null; blockSequenced = false },
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
                                // Phase: only for DURING tasks of a block that has phases.
                                if (blockPlacement == BlockTaskPlacement.DURING && blockPhases.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Phase",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        FilterChip(
                                            selected = blockPhaseId == null,
                                            onClick  = { blockPhaseId = null },
                                            label    = { Text("None") }
                                        )
                                        blockPhases.forEach { phase ->
                                            FilterChip(
                                                selected = blockPhaseId == phase.id,
                                                onClick  = { blockPhaseId = phase.id },
                                                label    = { Text(phase.name) }
                                            )
                                        }
                                    }
                                    Text(
                                        text = if (blockPhaseId == null) "Anywhere in the block outside its phases"
                                               else "Runs inside this phase",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                    )
                                }
                                // Sub-placement position: only shown for DURING tasks
                                if (blockPlacement == BlockTaskPlacement.DURING) {
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Sequenced", style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                if (blockPhaseId != null) "Runs in a fixed order relative to other sequenced tasks in this phase"
                                                else "Runs in a fixed order relative to other sequenced tasks in this block",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                        }
                                        Switch(
                                            checked = blockSequenced,
                                            onCheckedChange = { blockSequenced = it; if (it) blockSubPlace = null }
                                        )
                                    }
                                }
                                // Sub-placement position: only shown for DURING tasks not in a sequence —
                                // ordering already answers where a sequenced task starts.
                                if (blockPlacement == BlockTaskPlacement.DURING && !blockSequenced) {
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

                    // Time of day — one unified, mutually-exclusive "when" preference: a loose
                    // zone of the day (floating tasks only), a precise soft anchor, or a hard
                    // two-sided window. Available in both modes — the scheduler already honors
                    // AroundTime/TimeWindow for block tasks via the same Before/During/AfterBlock
                    // placement paths used for floating tasks.
                    FormSection(title = "Time of day") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (!isBlockMode) {
                                    FilterChip(
                                        selected = aroundMode == "zone" && zone == null,
                                        onClick  = { aroundMode = "zone"; zone = null; aroundTime = null; afterTime = null; beforeTime = null },
                                        label    = { Text("Any") }
                                    )
                                    FilterChip(
                                        selected = aroundMode == "zone" && zone == PlannerZone.MORNING,
                                        onClick  = { aroundMode = "zone"; zone = PlannerZone.MORNING; aroundTime = null; afterTime = null; beforeTime = null },
                                        label    = { Text("Morning") }
                                    )
                                    FilterChip(
                                        selected = aroundMode == "zone" && zone == PlannerZone.AFTERNOON,
                                        onClick  = { aroundMode = "zone"; zone = PlannerZone.AFTERNOON; aroundTime = null; afterTime = null; beforeTime = null },
                                        label    = { Text("Afternoon") }
                                    )
                                    FilterChip(
                                        selected = aroundMode == "zone" && zone == PlannerZone.EVENING,
                                        onClick  = { aroundMode = "zone"; zone = PlannerZone.EVENING; aroundTime = null; afterTime = null; beforeTime = null },
                                        label    = { Text("Evening") }
                                    )
                                }
                                FilterChip(
                                    selected = aroundMode == "anchor",
                                    onClick  = { aroundMode = "anchor"; zone = null; afterTime = null; beforeTime = null },
                                    label    = { Text("Around a time") }
                                )
                                FilterChip(
                                    selected = aroundMode == "window",
                                    onClick  = { aroundMode = "window"; zone = null; aroundTime = null },
                                    label    = { Text("Between two times") }
                                )
                            }

                            when (aroundMode) {
                                "zone" -> Text(
                                    text = when (zone) {
                                        PlannerZone.MORNING   -> "Scheduled first thing after wake"
                                        PlannerZone.AFTERNOON -> "Scheduled in the middle third of your day"
                                        PlannerZone.EVENING   -> "Scheduled as late as possible before sleep"
                                        null                  -> "No preference — fills wherever it fits"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                )

                                "anchor" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "A soft target — the task lands as close to this time as it can, drifting " +
                                            "earlier or later within the flex range if the exact slot is busy.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                    if (aroundTime != null) {
                                        Row(
                                            verticalAlignment     = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            TimePickerChip(value = aroundTime!!, onValueChange = { aroundTime = it })
                                            IconButton(onClick = { aroundTime = null }, modifier = Modifier.size(20.dp)) {
                                                Icon(Icons.Default.Close, null, modifier = Modifier.size(12.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                            }
                                        }
                                    } else {
                                        FilterChip(
                                            selected = false,
                                            onClick  = { showAroundTimePicker = true },
                                            label    = { Text("+ Time") }
                                        )
                                    }
                                    if (aroundTime != null) {
                                        Text(
                                            "Flex range",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement   = Arrangement.spacedBy(4.dp)
                                        ) {
                                            listOf(15 to "±15m", 30 to "±30m", 60 to "±1h", 120 to "±2h", 180 to "±3h")
                                                .forEach { (mins, label) ->
                                                    FilterChip(
                                                        selected = aroundFlexMinutes == mins,
                                                        onClick  = { aroundFlexMinutes = mins },
                                                        label    = { Text(label) }
                                                    )
                                                }
                                        }
                                    }
                                }

                                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { // "window"
                                    Text(
                                        "Placed somewhere in this range — spaced out through it for lower-priority " +
                                            "tasks, while Critical/Urgent tasks still take the first opening.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                    Row(
                                        verticalAlignment     = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (afterTime != null) {
                                            TimePickerChip(value = afterTime!!, onValueChange = { afterTime = it })
                                        } else {
                                            FilterChip(
                                                selected = false,
                                                onClick  = { showAfterTimePicker = true },
                                                label    = { Text("+ From") }
                                            )
                                        }
                                        Text("to", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (beforeTime != null) {
                                            TimePickerChip(value = beforeTime!!, onValueChange = { beforeTime = it })
                                        } else {
                                            FilterChip(
                                                selected = false,
                                                onClick  = { showBeforeTimePicker = true },
                                                label    = { Text("+ To") }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Tags: the shared tag bar (same as blocks'). A time After/Before is a tag
                    // too, unless Time of day already uses them as its window.
                    FormSection(title = "Tags") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ConstraintTagBar(
                                tags = tags,
                                onChange = { tags = it },
                                tasks = chainTargets,
                                blocks = availableBlocks,
                                calendarEvents = todayCalEvents,
                                afterTime = afterTime.takeIf { aroundMode != "window" },
                                beforeTime = beforeTime.takeIf { aroundMode != "window" },
                                onTimesChange = if (aroundMode != "window") { after, before ->
                                    afterTime = after
                                    beforeTime = before
                                    if (after != null || before != null) aroundTime = null
                                } else null,
                                afterConflicts = { isAfterConflicting(it) },
                                beforeConflicts = { isBeforeConflicting(it) },
                                repeatTags = isBlockMode || !once,
                                offerOnce = isBlockMode
                            )
                            // Time of day's From / To.
                            if (showAfterTimePicker) {
                                TimePickerDialog(
                                    initialHour   = afterTime?.split(":")?.getOrNull(0)?.toIntOrNull() ?: 9,
                                    initialMinute = afterTime?.split(":")?.getOrNull(1)?.toIntOrNull() ?: 0,
                                    onDismiss = { showAfterTimePicker = false },
                                    onConfirm = { h, m ->
                                        afterTime = "%02d:%02d".format(h, m)
                                        aroundTime = null
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
                                        aroundTime = null
                                        showBeforeTimePicker = false
                                    }
                                )
                            }
                            if (showAroundTimePicker) {
                                TimePickerDialog(
                                    initialHour   = aroundTime?.split(":")?.getOrNull(0)?.toIntOrNull() ?: 12,
                                    initialMinute = aroundTime?.split(":")?.getOrNull(1)?.toIntOrNull() ?: 0,
                                    onDismiss = { showAroundTimePicker = false },
                                    onConfirm = { h, m ->
                                        aroundTime = "%02d:%02d".format(h, m)
                                        afterTime = null
                                        beforeTime = null
                                        showAroundTimePicker = false
                                    }
                                )
                            }
                        }
                    }

                    // Advanced — buffer, routine, measured duration, and chains are power-user
                    // features most task adds never touch; collapsed by default keeps the
                    // common "add a task in 15 seconds" path from scrolling past all of it.
                    var showAdvanced by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { showAdvanced = !showAdvanced }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Advanced",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (showAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (showAdvanced) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AnimatedVisibility(visible = showAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {

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
                        if (!isBlockMode) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Remind me", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "Notify when the plan says it's time to start",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                                Switch(checked = remindAtStart, onCheckedChange = { remindAtStart = it })
                            }
                        }
                    }

                    // Chains — hidden for block tasks: they can save triggers, but nothing ever
                    // fires them for a BlockTask (applyTriggers only runs against
                    // TaskManagerScript's floating-task list).
                    if (!isBlockMode)
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

                    } // Advanced Column
                    } // AnimatedVisibility
                }
            }
        }
    }
}

private fun fmtPreviewTime(ms: Long): String =
    Calendar.getInstance().apply { timeInMillis = ms }
        .let { "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }

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
        val cur = queue.removeAt(0)
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

