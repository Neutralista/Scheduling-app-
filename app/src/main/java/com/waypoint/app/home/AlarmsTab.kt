package com.waypoint.app.home

import com.waypoint.app.AppLogger
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.waypoint.app.alarm.AlarmBlockSync
import com.waypoint.app.alarm.AlarmEntry
import com.waypoint.app.alarm.AlarmSignals
import com.waypoint.app.planner.NamedBlock
import com.waypoint.app.planner.NamedBlockStore
import com.waypoint.app.planner.SleepScheduleStore
import com.waypoint.app.ui.components.TimePickerDialog
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import androidx.compose.ui.platform.LocalContext

@Composable
fun AlarmsTab(
    alarms: AlarmSignals,
    sleepTimesFlow: StateFlow<Pair<Long?, Long?>>
) {
    val context = LocalContext.current
    val sleepStore = remember { SleepScheduleStore(context) }
    val blockStore = remember { NamedBlockStore(context) }
    val syncableBlocks = remember { blockStore.loadAllBlocks().filter { !it.isFloating } }
    val alarmList by alarms.alarmsFlow.collectAsState()
    val sleepTimes by sleepTimesFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<AlarmEntry?>(null) }

    val initialConfig = remember { sleepStore.load() }
    var wakeAlarmCount by remember { mutableIntStateOf(initialConfig.wakeAlarmCount) }
    var wakeAlarmIntervalMinutes by remember { mutableIntStateOf(initialConfig.wakeAlarmIntervalMinutes) }
    var preSleepReminderMinutes by remember { mutableIntStateOf(initialConfig.preSleepReminderMinutes) }
    var preSleepAlarmEnabled by remember { mutableStateOf(initialConfig.preSleepAlarmEnabled) }
    var bedtimeAlarmEnabled by remember { mutableStateOf(initialConfig.bedtimeAlarmEnabled) }
    var gentleWakeEnabled by remember { mutableStateOf(initialConfig.gentleWakeEnabled) }
    var mediumWakeEnabled by remember { mutableStateOf(initialConfig.mediumWakeEnabled) }
    var wakeAlarmEnabled by remember { mutableStateOf(initialConfig.wakeAlarmEnabled) }
    var preSleepSync by remember { mutableStateOf(initialConfig.preSleepSync) }
    var bedtimeSync by remember { mutableStateOf(initialConfig.bedtimeSync) }
    var gentleWakeSync by remember { mutableStateOf(initialConfig.gentleWakeSync) }
    var mediumWakeSync by remember { mutableStateOf(initialConfig.mediumWakeSync) }
    var wakeUpSync by remember { mutableStateOf(initialConfig.wakeUpSync) }

    val (bedMs, wakeMs) = sleepTimes
    val hasSleepTimes = bedMs != null && wakeMs != null

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { editTarget = null; showDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add alarm")
            }
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            if (!hasSleepTimes && alarmList.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "No alarms",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tap + to add one",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (hasSleepTimes) {
                        item {
                            Text(
                                "Sleep",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                            )
                            val intervalMs = wakeAlarmIntervalMinutes * 60_000L

                            if (preSleepReminderMinutes > 0) {
                                SleepAlarmRow(
                                    label = "Pre-sleep reminder",
                                    epochMs = bedMs!! - preSleepReminderMinutes * 60_000L,
                                    enabled = preSleepAlarmEnabled,
                                    onToggle = { e ->
                                        preSleepAlarmEnabled = e
                                        scope.launch { sleepStore.setSleepAlarmEnabled("pre_sleep", e) }
                                    },
                                    syncableBlocks = syncableBlocks,
                                    sync = preSleepSync,
                                    onSyncChange = { newSync ->
                                        preSleepSync = newSync
                                        scope.launch {
                                            sleepStore.setSleepAlarmSync("pre_sleep", newSync)
                                            AlarmBlockSync.sync(context)
                                            preSleepAlarmEnabled = sleepStore.load().preSleepAlarmEnabled
                                        }
                                    }
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            SleepAlarmRow(
                                label = "Bedtime",
                                epochMs = bedMs!!,
                                enabled = bedtimeAlarmEnabled,
                                onToggle = { e ->
                                    bedtimeAlarmEnabled = e
                                    scope.launch { sleepStore.setSleepAlarmEnabled("bedtime", e) }
                                },
                                syncableBlocks = syncableBlocks,
                                sync = bedtimeSync,
                                onSyncChange = { newSync ->
                                    bedtimeSync = newSync
                                    scope.launch {
                                        sleepStore.setSleepAlarmSync("bedtime", newSync)
                                        AlarmBlockSync.sync(context)
                                        bedtimeAlarmEnabled = sleepStore.load().bedtimeAlarmEnabled
                                    }
                                }
                            )
                            Spacer(Modifier.height(8.dp))

                            if (wakeAlarmCount >= 3) {
                                SleepAlarmRow(
                                    label = "Gentle wake (35% volume)",
                                    epochMs = wakeMs!! - 2 * intervalMs,
                                    enabled = gentleWakeEnabled,
                                    onToggle = { e ->
                                        gentleWakeEnabled = e
                                        scope.launch { sleepStore.setSleepAlarmEnabled("gentle_wake", e) }
                                    },
                                    syncableBlocks = syncableBlocks,
                                    sync = gentleWakeSync,
                                    onSyncChange = { newSync ->
                                        gentleWakeSync = newSync
                                        scope.launch {
                                            sleepStore.setSleepAlarmSync("gentle_wake", newSync)
                                            AlarmBlockSync.sync(context)
                                            gentleWakeEnabled = sleepStore.load().gentleWakeEnabled
                                        }
                                    }
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            if (wakeAlarmCount >= 2) {
                                SleepAlarmRow(
                                    label = "Medium wake (70% volume)",
                                    epochMs = wakeMs!! - intervalMs,
                                    enabled = mediumWakeEnabled,
                                    onToggle = { e ->
                                        mediumWakeEnabled = e
                                        scope.launch { sleepStore.setSleepAlarmEnabled("medium_wake", e) }
                                    },
                                    syncableBlocks = syncableBlocks,
                                    sync = mediumWakeSync,
                                    onSyncChange = { newSync ->
                                        mediumWakeSync = newSync
                                        scope.launch {
                                            sleepStore.setSleepAlarmSync("medium_wake", newSync)
                                            AlarmBlockSync.sync(context)
                                            mediumWakeEnabled = sleepStore.load().mediumWakeEnabled
                                        }
                                    }
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            SleepAlarmRow(
                                label = "Wake up!",
                                epochMs = wakeMs!!,
                                enabled = wakeAlarmEnabled,
                                onToggle = { e ->
                                    wakeAlarmEnabled = e
                                    scope.launch { sleepStore.setSleepAlarmEnabled("wake_up", e) }
                                },
                                syncableBlocks = syncableBlocks,
                                sync = wakeUpSync,
                                onSyncChange = { newSync ->
                                    wakeUpSync = newSync
                                    scope.launch {
                                        sleepStore.setSleepAlarmSync("wake_up", newSync)
                                        AlarmBlockSync.sync(context)
                                        wakeAlarmEnabled = sleepStore.load().wakeAlarmEnabled
                                    }
                                }
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                    if (alarmList.isNotEmpty()) {
                        item {
                            Text(
                                "Alarms",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                            )
                        }
                        items(alarmList, key = { it.id }) { alarm ->
                            AlarmRow(
                                alarm = alarm,
                                onToggle = { enabled ->
                                    scope.launch {
                                        try { alarms.setEnabled(alarm.id, enabled) }
                                        catch (e: Throwable) { AppLogger.e("AlarmsTab", "setEnabled threw ${e.javaClass.name}: ${e.message}", e) }
                                    }
                                },
                                onEdit = { editTarget = alarm; showDialog = true },
                                onDelete = {
                                    scope.launch {
                                        try { alarms.delete(alarm.id) }
                                        catch (e: Throwable) { AppLogger.e("AlarmsTab", "delete threw ${e.javaClass.name}: ${e.message}", e) }
                                    }
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        AlarmEditDialog(
            initial = editTarget,
            syncableBlocks = syncableBlocks,
            onDismiss = { showDialog = false },
            onSave = { entry ->
                scope.launch {
                    try {
                        if (entry.id.isBlank()) alarms.add(entry) else alarms.update(entry)
                        AlarmBlockSync.sync(context)
                    } catch (e: Throwable) {
                        AppLogger.e("AlarmsTab", "save threw ${e.javaClass.name}: ${e.message}", e)
                    }
                }
                showDialog = false
            }
        )
    }
}

@Composable
private fun AlarmRow(
    alarm: AlarmEntry,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val isSynced = alarm.blockSyncEnabled && alarm.linkedBlockId != null
    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (alarm.enabled)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = alarm.displayTime,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (alarm.enabled)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
                if (alarm.label.isNotEmpty()) {
                    Text(
                        text = alarm.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = alarm.repeatLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                if (isSynced) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "⟳ block sync",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    )
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete alarm",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            Switch(
                checked = alarm.enabled,
                onCheckedChange = if (isSynced) null else onToggle
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditDialog(
    initial: AlarmEntry?,
    syncableBlocks: List<NamedBlock>,
    onDismiss: () -> Unit,
    onSave: (AlarmEntry) -> Unit
) {
    var hour by remember { mutableIntStateOf(initial?.hour ?: 7) }
    var minute by remember { mutableIntStateOf(initial?.minute ?: 0) }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var repeatDays by remember { mutableStateOf(initial?.repeatDays ?: emptySet()) }
    var vibrate by remember { mutableStateOf(initial?.vibrate ?: true) }
    var showTimePicker by remember { mutableStateOf(false) }
    var blockSyncEnabled by remember { mutableStateOf(initial?.blockSyncEnabled ?: false) }
    var linkedBlockId by remember { mutableStateOf(initial?.linkedBlockId) }
    var blockDropdownExpanded by remember { mutableStateOf(false) }

    val selectedBlockName = syncableBlocks.find { it.id == linkedBlockId }?.name ?: "None"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New Alarm" else "Edit Alarm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showTimePicker = true }
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "%02d:%02d".format(hour, minute),
                        style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )

                Text(
                    "Repeat",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AlarmEntry.DAY_LABELS.forEachIndexed { idx, dayLabel ->
                        val dayNum = idx + 1
                        FilterChip(
                            selected = dayNum in repeatDays,
                            onClick = {
                                repeatDays = if (dayNum in repeatDays)
                                    repeatDays - dayNum
                                else
                                    repeatDays + dayNum
                            },
                            label = { Text(dayLabel, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Vibrate", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = vibrate, onCheckedChange = { vibrate = it })
                }

                if (syncableBlocks.isNotEmpty()) {
                    HorizontalDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Sync with block", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Auto-enable when block is scheduled",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = blockSyncEnabled,
                            onCheckedChange = { blockSyncEnabled = it }
                        )
                    }

                    if (blockSyncEnabled) {
                        ExposedDropdownMenuBox(
                            expanded = blockDropdownExpanded,
                            onExpandedChange = { blockDropdownExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = selectedBlockName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Block") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = blockDropdownExpanded)
                                },
                                modifier = Modifier
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = blockDropdownExpanded,
                                onDismissRequest = { blockDropdownExpanded = false }
                            ) {
                                syncableBlocks.forEach { block ->
                                    DropdownMenuItem(
                                        text = { Text(block.name) },
                                        onClick = {
                                            linkedBlockId = block.id
                                            blockDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val syncOn = blockSyncEnabled && linkedBlockId != null
                onSave(
                    AlarmEntry(
                        id = initial?.id ?: "",
                        label = label.trim(),
                        hour = hour,
                        minute = minute,
                        enabled = initial?.enabled ?: true,
                        repeatDays = repeatDays,
                        vibrate = vibrate,
                        linkedBlockId = if (syncOn) linkedBlockId else null,
                        blockSyncEnabled = syncOn
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (showTimePicker) {
        TimePickerDialog(
            initialHour = hour,
            initialMinute = minute,
            onDismiss = { showTimePicker = false },
            onConfirm = { h, m ->
                hour = h
                minute = m
                showTimePicker = false
            }
        )
    }
}
