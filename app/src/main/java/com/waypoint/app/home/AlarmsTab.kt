package com.waypoint.app.home

import com.waypoint.app.AppLogger
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.waypoint.app.alarm.AlarmEntry
import com.waypoint.app.alarm.AlarmSignals
import kotlinx.coroutines.launch

@Composable
fun AlarmsTab(alarms: AlarmSignals) {
    val alarmList by alarms.alarmsFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<AlarmEntry?>(null) }

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
            if (alarmList.isEmpty()) {
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

    if (showDialog) {
        AlarmEditDialog(
            initial = editTarget,
            onDismiss = { showDialog = false },
            onSave = { entry ->
                scope.launch {
                    try {
                        if (entry.id.isBlank()) alarms.add(entry) else alarms.update(entry)
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
            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun AlarmEditDialog(
    initial: AlarmEntry?,
    onDismiss: () -> Unit,
    onSave: (AlarmEntry) -> Unit
) {
    var hour by remember { mutableIntStateOf(initial?.hour ?: 7) }
    var minute by remember { mutableIntStateOf(initial?.minute ?: 0) }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var repeatDays by remember { mutableStateOf(initial?.repeatDays ?: emptySet()) }
    var vibrate by remember { mutableStateOf(initial?.vibrate ?: true) }
    var hourText by remember { mutableStateOf("%02d".format(initial?.hour ?: 7)) }
    var minuteText by remember { mutableStateOf("%02d".format(initial?.minute ?: 0)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New Alarm" else "Edit Alarm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = hourText,
                        onValueChange = { v ->
                            hourText = v
                            v.toIntOrNull()?.coerceIn(0, 23)?.let { hour = it }
                        },
                        label = { Text("HH") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.width(72.dp)
                    )
                    Text(":", style = MaterialTheme.typography.headlineMedium)
                    OutlinedTextField(
                        value = minuteText,
                        onValueChange = { v ->
                            minuteText = v
                            v.toIntOrNull()?.coerceIn(0, 59)?.let { minute = it }
                        },
                        label = { Text("MM") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.width(72.dp)
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
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val h = hourText.toIntOrNull()?.coerceIn(0, 23) ?: hour
                val m = minuteText.toIntOrNull()?.coerceIn(0, 59) ?: minute
                onSave(
                    AlarmEntry(
                        id = initial?.id ?: "",
                        label = label.trim(),
                        hour = h,
                        minute = m,
                        enabled = initial?.enabled ?: true,
                        repeatDays = repeatDays,
                        vibrate = vibrate
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
