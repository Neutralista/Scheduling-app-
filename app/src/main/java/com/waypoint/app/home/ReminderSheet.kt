package com.waypoint.app.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.waypoint.app.notification.ReminderAlarms
import com.waypoint.app.planner.RecurrenceRule
import com.waypoint.app.planner.Reminder
import com.waypoint.app.planner.ReminderStore
import com.waypoint.app.planner.parseReminderTime
import com.waypoint.app.ui.components.RecurrencePicker
import com.waypoint.app.ui.components.TimePickerChip
import com.waypoint.app.ui.components.TimePickerDialog
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val NAG_CHOICES = listOf(0 to "Off", 5 to "5 min", 10 to "10 min", 15 to "15 min", 30 to "30 min", 60 to "1 hour")

/**
 * New or edit a reminder: its name, a note, the times a day it's due, whether it happens once
 * or repeats, and how often it rings again until it's done or skipped. Saving (or deleting)
 * re-arms its notifications.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReminderSheet(
    initial: Reminder? = null,
    /** The day a new reminder is for; it happens once, on it. */
    defaultDate: LocalDate = LocalDate.now(),
    /** A new reminder's first time ("HH:MM"), e.g. a free slot's start. */
    defaultTime: String? = null,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { ReminderStore(context) }
    val today = remember { LocalDate.now() }

    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var titleError by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    val times = remember {
        mutableStateListOf<String>().apply {
            addAll(initial?.times ?: listOf(defaultTime ?: LocalTime.now().plusHours(1).withMinute(0).let { "%02d:00".format(it.hour) }))
        }
    }
    val initOnce = initial?.rule as? RecurrenceRule.OneOff
    var once by remember { mutableStateOf(initial == null || initOnce != null) }
    var onceDate by remember {
        mutableStateOf(initOnce?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: defaultDate)
    }
    var repeatRule by remember {
        mutableStateOf(initial?.rule?.takeIf { it !is RecurrenceRule.OneOff } ?: RecurrenceRule.EveryNDays(1, today.toString()))
    }
    var nagMinutes by remember { mutableIntStateOf(initial?.nagMinutes ?: 15) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showAddTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun save() {
        titleError = title.isBlank()
        if (titleError || times.isEmpty()) return
        val reminder = Reminder(
            id = initial?.id ?: UUID.randomUUID().toString(),
            title = title.trim(),
            note = note.trim(),
            times = times.distinct().sortedBy { parseReminderTime(it) ?: LocalTime.MAX },
            rule = if (once) RecurrenceRule.OneOff(onceDate.toString()) else repeatRule,
            enabled = initial?.enabled ?: true,
            nagMinutes = nagMinutes
        )
        // Times or days may have changed: clear what's showing, then re-arm from the new version.
        initial?.let { ReminderAlarms.clear(context, it.id) }
        store.save(reminder)
        ReminderAlarms.rescheduleAll(context)
        onSaved()
    }

    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${initial.title}\"?") },
            text = { Text("Its notifications stop and it leaves the to-do list.") },
            confirmButton = {
                TextButton(onClick = {
                    ReminderAlarms.clear(context, initial.id)
                    store.delete(initial.id)
                    confirmDelete = false
                    onSaved()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Text(
                        if (initial == null) "Add Reminder" else "Edit Reminder",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    TextButton(onClick = ::save) { Text("Save", color = MaterialTheme.colorScheme.primary) }
                }
                HorizontalDivider()

                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    Section("Name") {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it; titleError = false },
                            label = { Text("e.g. Vitamin D, Allergy pill") },
                            isError = titleError,
                            supportingText = if (titleError) ({ Text("Name is required") }) else null,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            label = { Text("Note (optional), e.g. with food") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Section("Times") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            times.forEachIndexed { i, t ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TimePickerChip(value = t, onValueChange = { times[i] = it })
                                    if (times.size > 1) {
                                        IconButton(onClick = { times.removeAt(i) }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Default.Close, "Remove $t", modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                            AddPill("Time", onClick = { showAddTime = true })
                        }
                        Text(
                            "Add a time for each dose, e.g. 08:00 and 20:00.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }

                    Section("Happens") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = once, onClick = { once = true }, label = { Text("Once") })
                            FilterChip(selected = !once, onClick = { once = false }, label = { Text("Repeats") })
                        }
                        if (once) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilterChip(selected = onceDate == today, onClick = { onceDate = today }, label = { Text("Today") })
                                FilterChip(
                                    selected = onceDate == today.plusDays(1),
                                    onClick = { onceDate = today.plusDays(1) },
                                    label = { Text("Tomorrow") }
                                )
                                val other = onceDate != today && onceDate != today.plusDays(1)
                                FilterChip(
                                    selected = other,
                                    onClick = { showDatePicker = true },
                                    label = {
                                        Text(
                                            if (other) onceDate.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
                                            else "Pick a date"
                                        )
                                    }
                                )
                            }
                        } else {
                            RecurrencePicker(value = repeatRule, onChange = { repeatRule = it }, includeOnce = false)
                        }
                    }

                    Section("Until done, ring again every") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            NAG_CHOICES.forEach { (minutes, label) ->
                                FilterChip(selected = nagMinutes == minutes, onClick = { nagMinutes = minutes }, label = { Text(label) })
                            }
                        }
                        Text(
                            "The notification stays pinned until you tap Done or Skip; swiped away, it comes back.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }

                    if (initial != null) {
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("Delete reminder", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (showAddTime) {
        val last = times.lastOrNull()?.let { parseReminderTime(it) }
        TimePickerDialog(
            initialHour = last?.plusHours(4)?.hour ?: 9,
            initialMinute = last?.minute ?: 0,
            onDismiss = { showAddTime = false },
            onConfirm = { h, m ->
                val t = "%02d:%02d".format(h, m)
                if (t !in times) times += t
                showAddTime = false
            }
        )
    }
    if (showDatePicker) {
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = onceDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { onceDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = dpState) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        content()
    }
}
