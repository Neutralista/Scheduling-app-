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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

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

    val durationPresets = listOf(30, 60, 90, 120, 180)
    val initialDur = initial?.estimatedMinutes ?: 60
    var selectedDuration by remember { mutableIntStateOf(if (initialDur in durationPresets) initialDur else 0) }
    var customDurationText by remember { mutableStateOf(if (initialDur !in durationPresets) initialDur.toString() else "") }

    var canStartEarly by remember { mutableStateOf(initial?.canStartEarly ?: true) }
    var canRunLate by remember { mutableStateOf(initial?.canRunLate ?: true) }

    val recurringDays = remember { mutableStateListOf<Int>().also {
        it.addAll(initial?.recurringDays ?: emptyList())
    }}
    var defaultHour by remember { mutableIntStateOf(initial?.defaultStartHour ?: 9) }
    var defaultMinute by remember { mutableIntStateOf(initial?.defaultStartMinute ?: 0) }
    var showDefaultTimePicker by remember { mutableStateOf(false) }

    // Per-date overrides for next 14 days
    val today = remember { LocalDate.now() }
    val next14 = remember { (1..14).map { today.plusDays(it.toLong()) } }
    val dateFmt = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd") }
    // Map dateStr -> (enabled, hour, minute); null = use recurring default
    val dateOverrides = remember {
        mutableStateMapOf<String, Triple<Boolean, Int, Int>>().also { map ->
            if (initial != null) {
                next14.forEach { d ->
                    val stored = store.getSchedule(initial.id, d)
                    if (stored != null) {
                        map[d.format(dateFmt)] = Triple(stored.enabled, stored.startHour, stored.startMinute)
                    }
                }
            }
        }
    }
    var showDayTimePicker by remember { mutableStateOf<String?>(null) }

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
                        val dur = if (selectedDuration > 0) selectedDuration
                            else customDurationText.toIntOrNull()?.takeIf { it > 0 } ?: 60
                        val block = NamedBlock(
                            id = blockId,
                            name = name.trim(),
                            colorArgb = selectedColor,
                            estimatedMinutes = dur,
                            canStartEarly = canStartEarly,
                            canRunLate = canRunLate,
                            recurringDays = recurringDays.sorted(),
                            defaultStartHour = defaultHour,
                            defaultStartMinute = defaultMinute
                        )
                        store.saveBlock(block)
                        // Save per-date overrides
                        next14.forEach { d ->
                            val key = d.format(dateFmt)
                            val override = dateOverrides[key]
                            if (override != null) {
                                store.setSchedule(NamedBlockSchedule(blockId, key,
                                    override.first, override.second, override.third))
                            }
                        }
                        // Save tasks
                        tasks.forEach { store.saveTask(it) }
                        onSaved()
                    }) { Text("Save") }
                }
                HorizontalDivider()

                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
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
                                        .clickable { selectedColor = argb }
                                )
                            }
                        }
                    }

                    // Duration
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Estimated duration", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
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

                    // Recurring days + default time
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Recurring schedule", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DAY_LABELS.forEachIndexed { idx, lbl ->
                                val dow = idx + 1 // 1=Mon..7=Sun
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
                                TextButton(onClick = { showDefaultTimePicker = true }) {
                                    Text("%02d:%02d".format(defaultHour, defaultMinute),
                                        style = MaterialTheme.typography.bodyMedium)
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
                                    val override = dateOverrides[key]
                                    // Determine effective state
                                    val effectiveEnabled = override?.first ?: isRecurring
                                    val effectiveHour = override?.second ?: defaultHour
                                    val effectiveMinute = override?.third ?: defaultMinute
                                    val isModified = override != null

                                    DayScheduleCard(
                                        date = date,
                                        enabled = effectiveEnabled,
                                        hour = effectiveHour,
                                        minute = effectiveMinute,
                                        isModified = isModified,
                                        onToggle = {
                                            val newEnabled = !effectiveEnabled
                                            dateOverrides[key] = Triple(newEnabled, effectiveHour, effectiveMinute)
                                        },
                                        onTimeTap = { showDayTimePicker = key }
                                    )
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
                                Icon(Icons.Default.Add, null,
                                    modifier = Modifier.size(16.dp))
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

    // Default time picker dialog
    if (showDefaultTimePicker) {
        TimePickerDialog(
            initialHour = defaultHour,
            initialMinute = defaultMinute,
            onDismiss = { showDefaultTimePicker = false },
            onConfirm = { h, m -> defaultHour = h; defaultMinute = m; showDefaultTimePicker = false }
        )
    }

    // Per-day time picker dialog
    val dayPickerKey = showDayTimePicker
    if (dayPickerKey != null) {
        val cur = dateOverrides[dayPickerKey]
        TimePickerDialog(
            initialHour = cur?.second ?: defaultHour,
            initialMinute = cur?.third ?: defaultMinute,
            onDismiss = { showDayTimePicker = null },
            onConfirm = { h, m ->
                val enabled = dateOverrides[dayPickerKey]?.first ?: true
                dateOverrides[dayPickerKey] = Triple(enabled, h, m)
                showDayTimePicker = null
            }
        )
    }

    // Add / edit task sheet
    if (showAddTask || editingTask != null) {
        BlockTaskSheet(
            blockId = blockId,
            initial = editingTask,
            onDismiss = { showAddTask = false; editingTask = null },
            onSave = { saved ->
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
    isModified: Boolean,
    onToggle: () -> Unit,
    onTimeTap: () -> Unit
) {
    val dayName = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    val accent = MaterialTheme.colorScheme.primary
    val surf = MaterialTheme.colorScheme.surfaceVariant

    Column(
        Modifier
            .width(68.dp)
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
        verticalArrangement = Arrangement.spacedBy(4.dp)
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
            TextButton(onClick = onTimeTap) {
                Text(
                    "%02d:%02d".format(hour, minute),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
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
