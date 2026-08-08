package com.waypoint.app.cycle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.waypoint.app.ui.components.TimePickerChip
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycleLogSheet(
    cycle: Cycle,
    onDismiss: () -> Unit,
    onSave: (Cycle) -> Unit,
    onDelete: () -> Unit
) {
    var wakeMs   by remember(cycle) { mutableLongStateOf(cycle.wakeMillis) }
    var sleepMs  by remember(cycle) { mutableStateOf(cycle.sleepStartMillis) }
    var nextWakeMs by remember(cycle) { mutableStateOf(cycle.nextWakeMillis) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {

                // ── Top bar ──────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel")
                    }
                    Text(
                        text = if (cycle.isOpen) "Edit current cycle" else "Edit cycle",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    TextButton(onClick = {
                        onSave(cycle.copy(
                            wakeMillis       = wakeMs,
                            sleepStartMillis = sleepMs,
                            nextWakeMillis   = nextWakeMs
                        ))
                    }) { Text("Save") }
                }

                HorizontalDivider()

                // ── Body ─────────────────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Text(
                        text = Cycle.formatDate(cycle.wakeMillis),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Wake up
                    TimeField(label = "Woke up", millis = wakeMs, anchorMillis = null) {
                        wakeMs = it
                    }

                    // Fell asleep
                    NullableTimeField(
                        label      = "Fell asleep",
                        millis     = sleepMs,
                        addLabel   = "+ Log sleep",
                        defaultMs  = {
                            Instant.ofEpochMilli(wakeMs)
                                .atZone(ZoneId.systemDefault())
                                .withHour(22).withMinute(0).withSecond(0).withNano(0)
                                .toInstant().toEpochMilli()
                        },
                        anchorMillis = wakeMs,
                        onChanged  = { sleepMs = it }
                    )

                    // Next wake / close
                    NullableTimeField(
                        label     = if (cycle.isOpen) "Close cycle at" else "Next wake",
                        millis    = nextWakeMs,
                        addLabel  = if (cycle.isOpen) "+ Close cycle" else "+ Set next wake",
                        defaultMs = { wakeMs + 24 * 3600_000L },
                        anchorMillis = wakeMs,
                        onChanged = { nextWakeMs = it }
                    )

                    // Summary
                    val preview = cycle.copy(
                        wakeMillis       = wakeMs,
                        sleepStartMillis = sleepMs,
                        nextWakeMillis   = nextWakeMs
                    )
                    CycleSummary(preview)

                    Spacer(Modifier.height(16.dp))

                    // Delete
                    if (!showDeleteConfirm) {
                        OutlinedButton(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("Delete cycle") }
                    } else {
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Delete this cycle permanently?",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { showDeleteConfirm = false }) {
                                    Text("Cancel")
                                }
                                Button(
                                    onClick = onDelete,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) { Text("Delete") }
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

// ── Internal helpers ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(label: String, millis: Long, anchorMillis: Long?, onChanged: (Long) -> Unit) {
    var showDatePicker by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimePickerChip(
                value = Cycle.formatTime(millis),
                onValueChange = { hhmm ->
                    val parts = hhmm.split(":")
                    if (parts.size == 2) {
                        onChanged(Cycle.withTime(millis, parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0))
                    }
                }
            )
            TextButton(
                onClick = { showDatePicker = true },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text(Cycle.formatDate(millis), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showDatePicker) {
        DatePickerModal(initialMs = millis, onDismiss = { showDatePicker = false }) { utcMs ->
            onChanged(Cycle.withDate(millis, utcMs))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NullableTimeField(
    label: String,
    millis: Long?,
    addLabel: String,
    defaultMs: () -> Long,
    anchorMillis: Long,
    onChanged: (Long?) -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (millis == null) {
            TextButton(
                onClick = { onChanged(defaultMs()) },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
            ) { Text(addLabel) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimePickerChip(
                    value = Cycle.formatTime(millis),
                    onValueChange = { hhmm ->
                        val parts = hhmm.split(":")
                        if (parts.size == 2) {
                            onChanged(Cycle.withTime(millis, parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0))
                        }
                    }
                )
                TextButton(
                    onClick = { showDatePicker = true },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text(Cycle.formatDate(millis), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(
                    onClick = { onChanged(null) },
                    contentPadding = PaddingValues(4.dp)
                ) { Text("Clear", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }

    if (showDatePicker && millis != null) {
        DatePickerModal(initialMs = millis, onDismiss = { showDatePicker = false }) { utcMs ->
            onChanged(Cycle.withDate(millis, utcMs))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerModal(initialMs: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    // DatePicker works in UTC-midnight millis; convert local millis to UTC date millis for the initial value
    val utcInitial = Instant.ofEpochMilli(initialMs)
        .atZone(ZoneId.systemDefault()).toLocalDate()
        .let { java.time.LocalDate.of(it.year, it.month, it.dayOfMonth) }
        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    val state = rememberDatePickerState(initialSelectedDateMillis = utcInitial)

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onConfirm(it) }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    ) {
        DatePicker(state = state)
    }
}

@Composable
private fun CycleSummary(cycle: Cycle) {
    val total = cycle.totalDurationMs
    if (total <= 0) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Summary", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            buildString {
                append("Total: ${Cycle.formatDuration(total)}")
                append("  •  Awake: ${Cycle.formatDuration(cycle.awakeDurationMs)}")
                cycle.sleepDurationMs?.let { append("  •  Sleep: ${Cycle.formatDuration(it)}") }
            },
            style = MaterialTheme.typography.bodySmall
        )
    }
}
