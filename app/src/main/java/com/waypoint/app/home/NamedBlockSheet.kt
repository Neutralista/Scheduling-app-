package com.waypoint.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.waypoint.app.planner.BlockTask
import com.waypoint.app.planner.BlockTaskPlacement
import com.waypoint.app.planner.NamedBlock
import com.waypoint.app.planner.NamedBlockSchedule
import com.waypoint.app.planner.NamedBlockStore
import com.waypoint.app.planner.TaskConditionSpec
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

private enum class BlockSchedulingMode { FIXED, AUTO }
private enum class BlockAutoDay { ANY, WORK_DAYS, DAYS_OFF }

private val BLOCK_COLORS = listOf(
    0xFF4DB6AC.toInt(), // teal
    0xFF7986CB.toInt(), // indigo
    0xFFEF9A9A.toInt(), // red
    0xFFF48FB1.toInt(), // pink
    0xFFA5D6A7.toInt(), // green
    0xFFFFCC80.toInt(), // amber
    0xFF90CAF9.toInt(), // blue
    0xFFCE93D8.toInt(), // purple
)

private val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private data class DayOverride(
    val enabled: Boolean,
    val startH: Int, val startM: Int,
    val endH: Int = -1, val endM: Int = 0
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NamedBlockSheet(
    initial: NamedBlock? = null,
    store: NamedBlockStore,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val blockId = remember { initial?.id ?: UUID.randomUUID().toString() }

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var nameError by remember { mutableStateOf(false) }
    var selectedColor by remember { mutableIntStateOf(initial?.colorArgb ?: BLOCK_COLORS.first()) }

    // Duration mode state
    val durationPresets = listOf(30, 60, 90, 120, 180)
    val initialDur = initial?.estimatedMinutes ?: 60
    var selectedDuration by remember { mutableIntStateOf(if (initialDur in durationPresets) initialDur else 0) }
    var customDurationText by remember { mutableStateOf(if (initialDur !in durationPresets) initialDur.toString() else "") }

    // Time-range mode toggle: true when block has an explicit end time
    var useTimeRange by remember { mutableStateOf(initial != null && initial.defaultEndHour != -1) }

    var canStartEarly by remember { mutableStateOf(initial?.canStartEarly ?: true) }
    var canRunLate by remember { mutableStateOf(initial?.canRunLate ?: true) }

    val recurringDays = remember {
        mutableStateListOf<Int>().also { it.addAll(initial?.recurringDays ?: emptyList()) }
    }
    var defaultHour by remember { mutableIntStateOf(initial?.defaultStartHour ?: 9) }
    var defaultMinute by remember { mutableIntStateOf(initial?.defaultStartMinute ?: 0) }
    var showDefaultStartPicker by remember { mutableStateOf(false) }

    val initialEndHourValid = initial != null && initial.defaultEndHour != -1
    var defaultEndHour by remember {
        mutableIntStateOf(if (initialEndHourValid) initial!!.defaultEndHour else ((initial?.defaultStartHour ?: 9) + 1) % 24)
    }
    var defaultEndMinute by remember {
        mutableIntStateOf(if (initialEndHourValid) initial!!.defaultEndMinute else 0)
    }
    var showDefaultEndPicker by remember { mutableStateOf(false) }

    // Per-date overrides for the next 14 days starting today
    val today = remember { LocalDate.now() }
    val next14 = remember { (0..13).map { today.plusDays(it.toLong()) } }
    val dateFmt = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd") }
    val dateOverrides = remember {
        mutableStateMapOf<String, DayOverride>().also { map ->
            if (initial != null) {
                next14.forEach { d ->
                    val stored = store.getSchedule(initial.id, d)
                    if (stored != null) {
                        map[d.format(dateFmt)] = DayOverride(
                            stored.enabled, stored.startHour, stored.startMinute,
                            stored.endHour, stored.endMinute
                        )
                    }
                }
            }
        }
    }
    // Pair<dateKey, isStart>: true = start-time picker, false = end-time picker
    var showDayTimePicker by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    // Scheduling mode
    var schedulingMode by remember {
        mutableStateOf(if (initial?.isFloating == true) BlockSchedulingMode.AUTO else BlockSchedulingMode.FIXED)
    }

    // Auto-place conditions (used when schedulingMode == AUTO)
    var autoDay by remember {
        mutableStateOf(when {
            initial?.floatingConditions?.any { it.type == "workDayOnly" } == true -> BlockAutoDay.WORK_DAYS
            initial?.floatingConditions?.any { it.type == "dayOffOnly" }  == true -> BlockAutoDay.DAYS_OFF
            else -> BlockAutoDay.ANY
        })
    }
    val autoDow = remember {
        mutableStateListOf<Int>().also { list ->
            initial?.floatingConditions?.firstOrNull { it.type == "daysOfWeek" }
                ?.days?.let { list.addAll(it) }
        }
    }
    val initAutoTw = initial?.floatingConditions?.firstOrNull { it.type == "timeWindow" }
    var autoAfterEnabled by remember { mutableStateOf(initAutoTw?.start != null) }
    var autoAfterHour by remember {
        mutableIntStateOf(initAutoTw?.start?.split(":")?.getOrNull(0)?.toIntOrNull() ?: 8)
    }
    var autoAfterMinute by remember {
        mutableIntStateOf(initAutoTw?.start?.split(":")?.getOrNull(1)?.toIntOrNull() ?: 0)
    }
    var autoBeforeEnabled by remember { mutableStateOf(initAutoTw?.end != null) }
    var autoBeforeHour by remember {
        mutableIntStateOf(initAutoTw?.end?.split(":")?.getOrNull(0)?.toIntOrNull() ?: 22)
    }
    var autoBeforeMinute by remember {
        mutableIntStateOf(initAutoTw?.end?.split(":")?.getOrNull(1)?.toIntOrNull() ?: 0)
    }
    var showAutoAfterPicker by remember { mutableStateOf(false) }
    var showAutoBeforePicker by remember { mutableStateOf(false) }

    // Tasks
    val tasks = remember {
        mutableStateListOf<BlockTask>().also { list ->
            if (initial != null) list.addAll(store.loadTasksForBlock(initial.id))
        }
    }
    var showAddTask by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<BlockTask?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true)
    ) {
        Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {

                // Top bar
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Text(
                        if (initial == null) "New Block" else "Edit Block",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    TextButton(onClick = {
                        nameError = name.isBlank()
                        if (nameError) return@TextButton
                        val dur = if (!useTimeRange) {
                            if (selectedDuration > 0) selectedDuration
                            else customDurationText.toIntOrNull()?.takeIf { it > 0 } ?: 60
                        } else {
                            val startMins = defaultHour * 60 + defaultMinute
                            val endMins = defaultEndHour * 60 + defaultEndMinute
                            val diff = endMins - startMins
                            if (diff > 0) diff else (diff + 24 * 60)
                        }
                        val autoConditions: List<TaskConditionSpec> = if (schedulingMode == BlockSchedulingMode.AUTO) buildList {
                            when (autoDay) {
                                BlockAutoDay.WORK_DAYS -> add(TaskConditionSpec("workDayOnly"))
                                BlockAutoDay.DAYS_OFF  -> add(TaskConditionSpec("dayOffOnly"))
                                BlockAutoDay.ANY       -> Unit
                            }
                            if (autoDow.isNotEmpty()) add(TaskConditionSpec("daysOfWeek", days = autoDow.sorted()))
                            if (autoAfterEnabled || autoBeforeEnabled) add(TaskConditionSpec(
                                "timeWindow",
                                start = if (autoAfterEnabled) "%02d:%02d".format(autoAfterHour, autoAfterMinute) else null,
                                end   = if (autoBeforeEnabled) "%02d:%02d".format(autoBeforeHour, autoBeforeMinute) else null
                            ))
                        } else emptyList()
                        val block = NamedBlock(
                            id = blockId,
                            name = name.trim(),
                            colorArgb = selectedColor,
                            estimatedMinutes = dur,
                            canStartEarly = canStartEarly,
                            canRunLate = canRunLate,
                            recurringDays = if (schedulingMode == BlockSchedulingMode.FIXED) recurringDays.sorted() else emptyList(),
                            defaultStartHour = defaultHour,
                            defaultStartMinute = defaultMinute,
                            defaultEndHour = if (useTimeRange && schedulingMode == BlockSchedulingMode.FIXED) defaultEndHour else -1,
                            defaultEndMinute = if (useTimeRange && schedulingMode == BlockSchedulingMode.FIXED) defaultEndMinute else 0,
                            isFloating = schedulingMode == BlockSchedulingMode.AUTO,
                            floatingConditions = autoConditions
                        )
                        store.saveBlock(block)
                        if (schedulingMode == BlockSchedulingMode.FIXED) {
                            next14.forEach { d ->
                                val key = d.format(dateFmt)
                                val ov = dateOverrides[key]
                                if (ov != null) {
                                    store.setSchedule(NamedBlockSchedule(
                                        blockId = blockId, date = key,
                                        enabled = ov.enabled,
                                        startHour = ov.startH, startMinute = ov.startM,
                                        endHour = if (useTimeRange) ov.endH else -1,
                                        endMinute = if (useTimeRange) ov.endM else 0
                                    ))
                                }
                            }
                        }
                        tasks.forEach { store.saveTask(it) }
                        onSaved()
                    }) { Text("Save") }
                }
                HorizontalDivider()

                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {

                    // Name
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Name", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = name, onValueChange = { name = it; nameError = false },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("e.g. Gym, Work, Dance lessons") },
                            isError = nameError,
                            supportingText = if (nameError) ({ Text("Required") }) else null,
                            singleLine = true
                        )
                    }

                    // Color
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Color", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val isCustomColor = selectedColor !in BLOCK_COLORS
                        var hexInput by remember {
                            mutableStateOf(if (isCustomColor) "%06X".format(selectedColor and 0xFFFFFF) else "")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BLOCK_COLORS.forEach { argb ->
                                val c = Color(argb)
                                Box(
                                    Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(c)
                                        .then(if (selectedColor == argb)
                                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        else Modifier)
                                        .clickable { selectedColor = argb; hexInput = "" }
                                )
                            }
                            // Custom color swatch
                            val customSwatchColor = if (isCustomColor) Color(selectedColor)
                                                    else MaterialTheme.colorScheme.surfaceVariant
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(customSwatchColor)
                                    .then(if (isCustomColor)
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    else Modifier)
                                    .clickable {
                                        if (!isCustomColor) {
                                            selectedColor = 0xFF808080.toInt()
                                            hexInput = "808080"
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (!isCustomColor) {
                                    Icon(
                                        Icons.Default.Add, contentDescription = "Custom color",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                        if (isCustomColor) {
                            OutlinedTextField(
                                value = hexInput,
                                onValueChange = { s ->
                                    val filtered = s.filter { it.isLetterOrDigit() }.take(6).uppercase()
                                    hexInput = filtered
                                    if (filtered.length == 6) {
                                        runCatching { android.graphics.Color.parseColor("#$filtered") }
                                            .onSuccess { selectedColor = it or 0xFF000000.toInt() }
                                    }
                                },
                                prefix = { Text("#") },
                                label = { Text("Hex color") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // Duration — estimate chips OR time-range pickers
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Duration", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilterChip(
                                    selected = !useTimeRange,
                                    onClick = { useTimeRange = false },
                                    label = { Text("Estimate") }
                                )
                                FilterChip(
                                    selected = useTimeRange,
                                    onClick = { useTimeRange = true },
                                    label = { Text("Time range") }
                                )
                            }
                        }
                        if (!useTimeRange) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                durationPresets.forEach { min ->
                                    val lbl = when {
                                        min < 60 -> "${min}m"
                                        min % 60 == 0 -> "${min / 60}h"
                                        else -> "${min / 60}h ${min % 60}m"
                                    }
                                    FilterChip(
                                        selected = selectedDuration == min,
                                        onClick = { selectedDuration = min; customDurationText = "" },
                                        label = { Text(lbl) }
                                    )
                                }
                                FilterChip(
                                    selected = selectedDuration == 0,
                                    onClick = { selectedDuration = 0 },
                                    label = { Text("Other") }
                                )
                            }
                            if (selectedDuration == 0) {
                                OutlinedTextField(
                                    value = customDurationText,
                                    onValueChange = { customDurationText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Minutes") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true
                                )
                            }
                        }
                    }

                    // Options
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Options", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Tasks can pull start earlier",
                                    style = MaterialTheme.typography.bodyMedium)
                                Text("Before-block tasks expand the block's displayed start",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = canStartEarly, onCheckedChange = { canStartEarly = it })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Tasks can push end later",
                                    style = MaterialTheme.typography.bodyMedium)
                                Text("After-block tasks expand the block's displayed end",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = canRunLate, onCheckedChange = { canRunLate = it })
                        }
                    }

                    // Scheduling mode
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Scheduling", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = schedulingMode == BlockSchedulingMode.FIXED,
                                onClick = { schedulingMode = BlockSchedulingMode.FIXED },
                                label = { Text("Fixed schedule") }
                            )
                            FilterChip(
                                selected = schedulingMode == BlockSchedulingMode.AUTO,
                                onClick = { schedulingMode = BlockSchedulingMode.AUTO },
                                label = { Text("Auto-place") }
                            )
                        }

                        if (schedulingMode == BlockSchedulingMode.FIXED) {
                            // Recurring days + default start/end times
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    DAY_LABELS.forEachIndexed { idx, lbl ->
                                        val dow = idx + 1
                                        FilterChip(
                                            selected = dow in recurringDays,
                                            onClick = {
                                                if (dow in recurringDays) recurringDays.remove(dow)
                                                else recurringDays.add(dow)
                                            },
                                            label = { Text(lbl) }
                                        )
                                    }
                                }
                                if (recurringDays.isNotEmpty()) {
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Default start:", style = MaterialTheme.typography.bodyMedium)
                                        TextButton(onClick = { showDefaultStartPicker = true }) {
                                            Text("%02d:%02d".format(defaultHour, defaultMinute),
                                                style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                    if (useTimeRange) {
                                        Row(verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("Default end:", style = MaterialTheme.typography.bodyMedium)
                                            TextButton(onClick = { showDefaultEndPicker = true }) {
                                                Text("%02d:%02d".format(defaultEndHour, defaultEndMinute),
                                                    style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                    }
                                }
                            }

                            // Next 14 days strip
                            if (recurringDays.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Next 14 days", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(next14) { date ->
                                            val key = date.format(dateFmt)
                                            val dow = date.dayOfWeek.value
                                            val isRecurring = dow in recurringDays
                                            val ov = dateOverrides[key]
                                            val effectiveEnabled = ov?.enabled ?: isRecurring
                                            val effectiveStartH = ov?.startH ?: defaultHour
                                            val effectiveStartM = ov?.startM ?: defaultMinute
                                            val effectiveEndH = ov?.endH?.takeIf { it != -1 } ?: defaultEndHour
                                            val effectiveEndM = ov?.endM ?: defaultEndMinute
                                            val isModified = ov != null

                                            DayScheduleCard(
                                                date = date,
                                                enabled = effectiveEnabled,
                                                hour = effectiveStartH,
                                                minute = effectiveStartM,
                                                endHour = if (useTimeRange) effectiveEndH else -1,
                                                endMinute = effectiveEndM,
                                                isModified = isModified,
                                                onToggle = {
                                                    dateOverrides[key] = DayOverride(
                                                        !effectiveEnabled,
                                                        effectiveStartH, effectiveStartM,
                                                        effectiveEndH, effectiveEndM
                                                    )
                                                },
                                                onStartTimeTap = { showDayTimePicker = key to true },
                                                onEndTimeTap = { showDayTimePicker = key to false }
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            // Auto-place conditions
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    "The scheduler will find an available slot each day based on these conditions.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                // Day type
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Day", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilterChip(selected = autoDay == BlockAutoDay.ANY,
                                            onClick = { autoDay = BlockAutoDay.ANY },
                                            label = { Text("Any day") })
                                        FilterChip(selected = autoDay == BlockAutoDay.WORK_DAYS,
                                            onClick = { autoDay = BlockAutoDay.WORK_DAYS },
                                            label = { Text("Work days") })
                                        FilterChip(selected = autoDay == BlockAutoDay.DAYS_OFF,
                                            onClick = { autoDay = BlockAutoDay.DAYS_OFF },
                                            label = { Text("Days off") })
                                    }
                                }

                                // Days of week (optional)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Days of week (optional)", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        DAY_LABELS.forEachIndexed { idx, lbl ->
                                            val dow = idx + 1
                                            FilterChip(
                                                selected = dow in autoDow,
                                                onClick = {
                                                    if (dow in autoDow) autoDow.remove(dow) else autoDow.add(dow)
                                                },
                                                label = { Text(lbl) }
                                            )
                                        }
                                    }
                                }

                                // Time window (optional)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Time window (optional)", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Switch(checked = autoAfterEnabled, onCheckedChange = { autoAfterEnabled = it })
                                        Text("After", style = MaterialTheme.typography.bodyMedium)
                                        if (autoAfterEnabled) {
                                            TextButton(onClick = { showAutoAfterPicker = true }) {
                                                Text("%02d:%02d".format(autoAfterHour, autoAfterMinute))
                                            }
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Switch(checked = autoBeforeEnabled, onCheckedChange = { autoBeforeEnabled = it })
                                        Text("Before", style = MaterialTheme.typography.bodyMedium)
                                        if (autoBeforeEnabled) {
                                            TextButton(onClick = { showAutoBeforePicker = true }) {
                                                Text("%02d:%02d".format(autoBeforeHour, autoBeforeMinute))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Tasks section
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Tasks", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = { showAddTask = true }) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Add task")
                            }
                        }
                        if (tasks.isEmpty()) {
                            Text(
                                "No tasks yet. Add tasks that happen before, during, or after this block.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            tasks.forEach { task ->
                                BlockTaskRow(
                                    task = task,
                                    onEdit = { editingTask = task },
                                    onDelete = {
                                        tasks.remove(task)
                                        if (initial != null) store.deleteTask(task.id)
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    // Default start time picker
    if (showDefaultStartPicker) {
        TimePickerDialog(
            initialHour = defaultHour,
            initialMinute = defaultMinute,
            onDismiss = { showDefaultStartPicker = false },
            onConfirm = { h, m -> defaultHour = h; defaultMinute = m; showDefaultStartPicker = false }
        )
    }

    // Default end time picker (time-range mode only)
    if (showDefaultEndPicker) {
        TimePickerDialog(
            initialHour = defaultEndHour,
            initialMinute = defaultEndMinute,
            onDismiss = { showDefaultEndPicker = false },
            onConfirm = { h, m -> defaultEndHour = h; defaultEndMinute = m; showDefaultEndPicker = false }
        )
    }

    // Auto-place "After" time picker
    if (showAutoAfterPicker) {
        TimePickerDialog(
            initialHour = autoAfterHour,
            initialMinute = autoAfterMinute,
            onDismiss = { showAutoAfterPicker = false },
            onConfirm = { h, m -> autoAfterHour = h; autoAfterMinute = m; showAutoAfterPicker = false }
        )
    }

    // Auto-place "Before" time picker
    if (showAutoBeforePicker) {
        TimePickerDialog(
            initialHour = autoBeforeHour,
            initialMinute = autoBeforeMinute,
            onDismiss = { showAutoBeforePicker = false },
            onConfirm = { h, m -> autoBeforeHour = h; autoBeforeMinute = m; showAutoBeforePicker = false }
        )
    }

    // Per-day time picker
    val pickerTarget = showDayTimePicker
    if (pickerTarget != null) {
        val (dayKey, isStart) = pickerTarget
        val cur = dateOverrides[dayKey]
        val initH = if (isStart) cur?.startH ?: defaultHour
                    else cur?.endH?.takeIf { it != -1 } ?: defaultEndHour
        val initM = if (isStart) cur?.startM ?: defaultMinute
                    else cur?.endM ?: defaultEndMinute
        TimePickerDialog(
            initialHour = initH,
            initialMinute = initM,
            onDismiss = { showDayTimePicker = null },
            onConfirm = { h, m ->
                val base = dateOverrides[dayKey]
                    ?: DayOverride(true, defaultHour, defaultMinute, defaultEndHour, defaultEndMinute)
                dateOverrides[dayKey] = if (isStart) base.copy(startH = h, startM = m)
                                        else base.copy(endH = h, endM = m)
                showDayTimePicker = null
            }
        )
    }

    // Add / edit task sheet — full wizard in block mode
    if (showAddTask || editingTask != null) {
        AddTaskSheet(
            forBlock = blockId,
            initialBlockTask = editingTask,
            onDismiss = { showAddTask = false; editingTask = null },
            onSaveBlockTask = { saved ->
                val idx = tasks.indexOfFirst { it.id == saved.id }
                if (idx >= 0) tasks[idx] = saved else tasks.add(saved)
                showAddTask = false; editingTask = null
            }
        )
    }
}

@Composable
private fun DayScheduleCard(
    date: LocalDate,
    enabled: Boolean,
    hour: Int,
    minute: Int,
    endHour: Int = -1,
    endMinute: Int = 0,
    isModified: Boolean,
    onToggle: () -> Unit,
    onStartTimeTap: () -> Unit,
    onEndTimeTap: () -> Unit = {}
) {
    val dayName = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    val accent = MaterialTheme.colorScheme.primary
    val surf = MaterialTheme.colorScheme.surfaceVariant

    Column(
        Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) accent.copy(alpha = 0.10f) else surf.copy(alpha = 0.5f))
            .border(
                width = if (isModified) 1.5.dp else 1.dp,
                color = if (enabled) accent.copy(alpha = if (isModified) 0.55f else 0.28f)
                else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            dayName,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "${date.dayOfMonth}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Switch(checked = enabled, onCheckedChange = { onToggle() })
        if (enabled) {
            TextButton(
                onClick = onStartTimeTap,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text(
                    "%02d:%02d".format(hour, minute),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
            }
            if (endHour != -1) {
                TextButton(
                    onClick = onEndTimeTap,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text(
                        "→%02d:%02d".format(endHour, endMinute),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent.copy(alpha = 0.75f)
                    )
                }
            }
        }
    }
}

@Composable
private fun BlockTaskRow(
    task: BlockTask,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val placementColor = when (task.placement) {
        BlockTaskPlacement.BEFORE -> Color(0xFF7986CB)
        BlockTaskPlacement.DURING -> Color(0xFF4DB6AC)
        BlockTaskPlacement.AFTER  -> Color(0xFFEF9A9A)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val durLabel = if (task.durationMinutes < 60) "${task.durationMinutes}m"
                    else "${task.durationMinutes / 60}h ${task.durationMinutes % 60}m".trimEnd()
                Text(durLabel, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                PlacementBadge(task.placement, placementColor)
                Text(if (task.isAlways) "Always" else "Situational",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PlacementBadge(placement: BlockTaskPlacement, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            placement.name.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TimePicker(state = state)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("OK") }
                }
            }
        }
    }
}
