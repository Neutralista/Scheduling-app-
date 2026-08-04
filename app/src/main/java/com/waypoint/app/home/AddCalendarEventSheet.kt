package com.waypoint.app.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.waypoint.app.signal.CalendarEvent
import com.waypoint.app.ui.components.TimePickerChip
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

@Composable
fun AddCalendarEventSheet(
    date: LocalDate,
    onDismiss: () -> Unit,
    onSave: (title: String, startMs: Long, endMs: Long, notes: String, allDay: Boolean) -> Unit,
    initialCalEvent: CalendarEvent? = null
) {
    val isEditing = initialCalEvent != null
    val dateDisplay = remember(date) {
        date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.getDefault()))
    }

    var title by remember { mutableStateOf(initialCalEvent?.title ?: "") }
    var titleError by remember { mutableStateOf(false) }
    var allDay by remember { mutableStateOf(initialCalEvent?.allDay ?: false) }
    var startTime by remember { mutableStateOf(
        if (initialCalEvent != null) fmtMs(initialCalEvent.startMillis) else "09:00"
    ) }
    var endTime by remember { mutableStateOf(
        if (initialCalEvent != null) fmtMs(initialCalEvent.endMillis) else "10:00"
    ) }
    var notes by remember { mutableStateOf(initialCalEvent?.description ?: "") }

    fun save() {
        if (title.trim().isEmpty()) { titleError = true; return }

        val zone = ZoneId.systemDefault()
        val startMs: Long
        val endMs: Long

        if (allDay) {
            startMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
            endMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        } else {
            val sH = startTime.substringBefore(':').toIntOrNull() ?: 9
            val sM = startTime.substringAfter(':').toIntOrNull() ?: 0
            val eH = endTime.substringBefore(':').toIntOrNull() ?: 10
            val eM = endTime.substringAfter(':').toIntOrNull() ?: 0
            startMs = date.atTime(sH, sM).atZone(zone).toInstant().toEpochMilli()
            val rawEnd = date.atTime(eH, eM).atZone(zone).toInstant().toEpochMilli()
            endMs = if (rawEnd <= startMs) startMs + 3_600_000L else rawEnd
        }

        onSave(title.trim(), startMs, endMs, notes.trim(), allDay)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // ── Top bar ───────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Text(
                        text = if (isEditing) "Edit Event" else "New Event",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    TextButton(onClick = { save() }) {
                        Text("Save", fontWeight = FontWeight.SemiBold)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // ── Form ──────────────────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                ) {
                    Spacer(Modifier.height(16.dp))

                    // Title
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it; titleError = false },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = titleError,
                        singleLine = true,
                        supportingText = if (titleError) {
                            { Text("Title is required", color = MaterialTheme.colorScheme.error) }
                        } else null
                    )

                    Spacer(Modifier.height(20.dp))

                    // Date (read-only)
                    FormRow(label = "Date") {
                        Text(
                            text = dateDisplay,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // All day toggle
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "All day",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Switch(checked = allDay, onCheckedChange = { allDay = it })
                    }

                    // Time pickers
                    if (!allDay) {
                        Spacer(Modifier.height(16.dp))
                        FormRow(label = "From") {
                            TimePickerChip(
                                value = startTime,
                                onValueChange = { startTime = it }
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "to",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(12.dp))
                            TimePickerChip(
                                value = endTime,
                                onValueChange = { endTime = it }
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // Notes
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun FormRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp)
        )
        content()
    }
}

private fun fmtMs(ms: Long): String =
    Calendar.getInstance().apply { timeInMillis = ms }
        .let { "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }
