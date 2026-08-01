package com.waypoint.app.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.waypoint.app.planner.ShiftCalendarSync
import com.waypoint.app.planner.SleepCalendarSync
import com.waypoint.app.planner.SleepLogEntry
import com.waypoint.app.planner.SleepLogStore
import com.waypoint.app.signal.CalendarEvent
import com.waypoint.app.signal.CalendarSignals
import com.waypoint.app.signal.ShiftSession
import com.waypoint.app.signal.ShiftTime
import com.waypoint.app.signal.WorkScheduleSignals
import com.waypoint.app.ui.components.TimePickerChip
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

@Composable
fun ShiftLogSheet(
    ws: WorkScheduleSignals,
    calendarSignals: CalendarSignals,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sleepStore = remember { SleepLogStore(context) }

    var sessions by remember { mutableStateOf(ws.getRecentSessions(30)) }
    var sleepEntries by remember { mutableStateOf(sleepStore.loadRecent(30)) }
    var calEvents by remember { mutableStateOf<List<Pair<Long, CalendarEvent>>>(emptyList()) }

    var editTarget by remember { mutableStateOf<Pair<String, ShiftSession>?>(null) }
    var sleepEditTarget by remember { mutableStateOf<SleepLogEntry?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showSleepAdd by remember { mutableStateOf(false) }

    val shiftLinkedIds by remember(sessions) {
        derivedStateOf { sessions.mapNotNull { it.second.calendarEventId }.toSet() }
    }
    val sleepLinkedIds by remember(sleepEntries) {
        derivedStateOf { sleepEntries.mapNotNull { it.calendarEventId }.toSet() }
    }
    val shiftOrphans by remember(calEvents, shiftLinkedIds) {
        derivedStateOf { calEvents.filter { (id, ev) -> id !in shiftLinkedIds && ev.title.contains("shift", ignoreCase = true) } }
    }
    val sleepOrphans by remember(calEvents, sleepLinkedIds) {
        derivedStateOf { calEvents.filter { (id, ev) -> id !in sleepLinkedIds && ev.title == "Sleep" } }
    }

    fun reloadSessions() { sessions = ws.getRecentSessions(30) }
    fun reloadSleep() { sleepEntries = sleepStore.loadRecent(30) }
    fun reloadCalendar() { scope.launch { calEvents = calendarSignals.queryWaypointEvents(30) } }
    fun reload() { reloadSessions(); reloadSleep(); reloadCalendar() }

    LaunchedEffect(Unit) { calEvents = calendarSignals.queryWaypointEvents(30) }

    val isEmpty = sessions.isEmpty() && sleepEntries.isEmpty() && calEvents.isEmpty()

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
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Text(
                        "Log",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (isEmpty) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Nothing logged in the last 30 days",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        // ── Shifts ──────────────────────────────────────────────────────
                        item { LogSectionHeader("SHIFTS", onAdd = { showAdd = true }) }
                        if (sessions.isEmpty() && shiftOrphans.isEmpty()) {
                            item {
                                Text(
                                    "No shifts logged",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                )
                            }
                        }
                        items(sessions, key = { "s_${it.first}" }) { (dateKey, session) ->
                            ShiftLogRow(
                                dateKey = dateKey,
                                session = session,
                                onEdit = { editTarget = dateKey to session },
                                onDelete = {
                                    scope.launch {
                                        session.calendarEventId?.let { ShiftCalendarSync.delete(context, it) }
                                        ws.deleteSession(dateKey)
                                        reload()
                                    }
                                }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                        items(shiftOrphans, key = { "sc_${it.first}" }) { (id, event) ->
                            CalendarEventRow(
                                event = event,
                                label = "orphan",
                                onDelete = {
                                    scope.launch {
                                        calendarSignals.deleteEvent(id)
                                        reloadCalendar()
                                    }
                                }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }

                        // ── Sleep ───────────────────────────────────────────────────────
                        item { LogSectionHeader("SLEEP", onAdd = { showSleepAdd = true }) }
                        if (sleepEntries.isEmpty() && sleepOrphans.isEmpty()) {
                            item {
                                Text(
                                    "No sleep logged",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                )
                            }
                        }
                        items(sleepEntries, key = { "sl_${it.dateIso}" }) { entry ->
                            SleepLogRow(
                                entry = entry,
                                onEdit = { sleepEditTarget = entry },
                                onDelete = {
                                    scope.launch {
                                        entry.calendarEventId?.let { calendarSignals.deleteEvent(it) }
                                        sleepStore.deleteEntry(entry.dateIso)
                                        reload()
                                    }
                                }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                        items(sleepOrphans, key = { "slc_${it.first}" }) { (id, event) ->
                            SleepOrphanRow(
                                event = event,
                                onAdopt = {
                                    scope.launch {
                                        sleepStore.saveEntry(SleepLogEntry(
                                            dateIso = dateKeyFromMs(event.endMillis),
                                            bedMillis = event.startMillis,
                                            wakeMillis = event.endMillis,
                                            calendarEventId = id
                                        ))
                                        reloadSleep()
                                        reloadCalendar()
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        calendarSignals.deleteEvent(id)
                                        reloadCalendar()
                                    }
                                }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }

    editTarget?.let { (dateKey, session) ->
        ShiftEditDialog(
            dateKey = dateKey,
            session = session,
            onSave = { updated ->
                scope.launch {
                    ws.saveSession(dateKey, updated)
                    reload()
                    editTarget = null
                }
            },
            onDelete = {
                scope.launch {
                    session.calendarEventId?.let { ShiftCalendarSync.delete(context, it) }
                    ws.deleteSession(dateKey)
                    reload()
                    editTarget = null
                }
            },
            onDismiss = { editTarget = null }
        )
    }

    if (showAdd) {
        ShiftAddDialog(
            ws = ws,
            onSave = { dateKey, session ->
                scope.launch {
                    ws.saveSession(dateKey, session)
                    reloadSessions()
                    showAdd = false
                }
            },
            onDismiss = { showAdd = false }
        )
    }

    sleepEditTarget?.let { entry ->
        SleepEditDialog(
            entry = entry,
            onSave = { updated ->
                scope.launch {
                    val eventId = SleepCalendarSync.writeForEntry(context, updated.bedMillis, updated.wakeMillis, updated.calendarEventId)
                    sleepStore.saveEntry(if (eventId > 0) updated.copy(calendarEventId = eventId) else updated)
                    reloadSleep()
                    sleepEditTarget = null
                }
            },
            onDelete = {
                scope.launch {
                    entry.calendarEventId?.let { calendarSignals.deleteEvent(it) }
                    sleepStore.deleteEntry(entry.dateIso)
                    reload()
                    sleepEditTarget = null
                }
            },
            onDismiss = { sleepEditTarget = null }
        )
    }

    if (showSleepAdd) {
        SleepAddDialog(
            onSave = { entry ->
                scope.launch {
                    val eventId = SleepCalendarSync.writeForEntry(context, entry.bedMillis, entry.wakeMillis, null)
                    sleepStore.saveEntry(if (eventId > 0) entry.copy(calendarEventId = eventId) else entry)
                    reloadSleep()
                    showSleepAdd = false
                }
            },
            onDismiss = { showSleepAdd = false }
        )
    }
}

// ── Section header ────────────────────────────────────────────────────────────

@Composable
private fun LogSectionHeader(title: String, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onAdd) {
            Text("Add", style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ── Shift rows ────────────────────────────────────────────────────────────────

@Composable
private fun ShiftLogRow(
    dateKey: String,
    session: ShiftSession,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val label = remember(dateKey) { shiftDateLabel(dateKey) }
    val startStr = session.actualStartMillis?.let { shiftFormatMs(it) } ?: "--:--"
    val endStr = session.actualEndMillis?.let { shiftFormatMs(it) } ?: "active"
    val durStr = if (session.actualStartMillis != null && session.actualEndMillis != null)
        shiftElapsed(session.actualEndMillis - session.actualStartMillis) else ""

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "$startStr – $endStr${if (durStr.isNotEmpty()) "  ·  $durStr" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete session", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
        }
    }
}

// ── Sleep rows ────────────────────────────────────────────────────────────────

@Composable
private fun SleepLogRow(
    entry: SleepLogEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val label = remember(entry.dateIso) { shiftDateLabel(entry.dateIso) }
    val bedStr = shiftFormatMs(entry.bedMillis)
    val wakeStr = shiftFormatMs(entry.wakeMillis)
    val durStr = shiftElapsed(entry.wakeMillis - entry.bedMillis)

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "$bedStr – $wakeStr  ·  $durStr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete sleep entry", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
        }
    }
}

// ── Calendar event row (orphans) ──────────────────────────────────────────────

@Composable
private fun CalendarEventRow(event: CalendarEvent, label: String = "orphan", onDelete: () -> Unit) {
    val dateLabel = remember(event.startMillis) { shiftDateLabel(dateKeyFromMs(event.startMillis)) }
    val startStr = shiftFormatMs(event.startMillis)
    val endStr = shiftFormatMs(event.endMillis)
    val durStr = shiftElapsed(event.endMillis - event.startMillis)

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    dateLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            Text(
                "$startStr – $endStr  ·  $durStr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete calendar event", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun SleepOrphanRow(event: CalendarEvent, onAdopt: () -> Unit, onDelete: () -> Unit) {
    val dateLabel = remember(event.startMillis) { shiftDateLabel(dateKeyFromMs(event.startMillis)) }
    val startStr = shiftFormatMs(event.startMillis)
    val endStr = shiftFormatMs(event.endMillis)
    val durStr = shiftElapsed(event.endMillis - event.startMillis)

    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    dateLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "orphan",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            Text(
                "$startStr – $endStr  ·  $durStr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onAdopt) { Text("Adopt", style = MaterialTheme.typography.labelMedium) }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete calendar event", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
        }
    }
}

// ── Shift dialogs ─────────────────────────────────────────────────────────────

@Composable
private fun ShiftEditDialog(
    dateKey: String,
    session: ShiftSession,
    onSave: (ShiftSession) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val label = remember(dateKey) { shiftDateLabel(dateKey) }
    var startTime by remember { mutableStateOf(session.actualStartMillis?.let { shiftFormatMs(it) } ?: "09:00") }
    var endTime by remember {
        mutableStateOf(session.actualEndMillis?.let { shiftFormatMs(it) } ?: shiftFormatMs(System.currentTimeMillis()))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit shift · $label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Start", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(40.dp))
                    TimePickerChip(value = startTime, onValueChange = { startTime = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("End", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(40.dp))
                    TimePickerChip(value = endTime, onValueChange = { endTime = it })
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete this session") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val startMs = shiftParseTime(dateKey, startTime) ?: return@TextButton
                val endMs = shiftParseTime(dateKey, endTime, afterMs = startMs) ?: return@TextButton
                onSave(session.copy(actualStartMillis = startMs, actualEndMillis = endMs))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ShiftAddDialog(
    ws: WorkScheduleSignals,
    onSave: (String, ShiftSession) -> Unit,
    onDismiss: () -> Unit
) {
    var dateKey by remember { mutableStateOf(ws.dateKey(Calendar.getInstance())) }
    var startTime by remember { mutableStateOf("09:00") }
    var endTime by remember { mutableStateOf("17:00") }
    var dateError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add shift") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = dateKey,
                    onValueChange = { dateKey = it; dateError = false },
                    label = { Text("Date (yyyy-MM-dd)") },
                    isError = dateError,
                    supportingText = if (dateError) ({ Text("Invalid date format") }) else null,
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Start", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(40.dp))
                    TimePickerChip(value = startTime, onValueChange = { startTime = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("End", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(40.dp))
                    TimePickerChip(value = endTime, onValueChange = { endTime = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try { LocalDate.parse(dateKey) } catch (_: Exception) { dateError = true; return@TextButton }
                val startMs = shiftParseTime(dateKey, startTime) ?: return@TextButton
                val endMs = shiftParseTime(dateKey, endTime, afterMs = startMs) ?: return@TextButton
                onSave(dateKey, ShiftSession(actualStartMillis = startMs, actualEndMillis = endMs))
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Sleep dialogs ─────────────────────────────────────────────────────────────

@Composable
private fun SleepEditDialog(
    entry: SleepLogEntry,
    onSave: (SleepLogEntry) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val label = remember(entry.dateIso) { shiftDateLabel(entry.dateIso) }
    var bedTime by remember { mutableStateOf(shiftFormatMs(entry.bedMillis)) }
    var wakeTime by remember { mutableStateOf(shiftFormatMs(entry.wakeMillis)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit sleep · $label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Bed", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(40.dp))
                    TimePickerChip(value = bedTime, onValueChange = { bedTime = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Wake", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(40.dp))
                    TimePickerChip(value = wakeTime, onValueChange = { wakeTime = it })
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete this entry") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Use the original bed date as the parse base; afterMs pushes wake to next day if needed
                val bedDateKey = dateKeyFromMs(entry.bedMillis)
                val bedMs = shiftParseTime(bedDateKey, bedTime) ?: return@TextButton
                val wakeMs = shiftParseTime(bedDateKey, wakeTime, afterMs = bedMs) ?: return@TextButton
                onSave(entry.copy(bedMillis = bedMs, wakeMillis = wakeMs))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SleepAddDialog(
    onSave: (SleepLogEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var dateKey by remember { mutableStateOf(LocalDate.now().toString()) }
    var bedTime by remember { mutableStateOf("23:00") }
    var wakeTime by remember { mutableStateOf("07:00") }
    var dateError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add sleep") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = dateKey,
                    onValueChange = { dateKey = it; dateError = false },
                    label = { Text("Bed night (yyyy-MM-dd)") },
                    isError = dateError,
                    supportingText = if (dateError) ({ Text("Invalid date format") }) else null,
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Bed", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(40.dp))
                    TimePickerChip(value = bedTime, onValueChange = { bedTime = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Wake", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(40.dp))
                    TimePickerChip(value = wakeTime, onValueChange = { wakeTime = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try { LocalDate.parse(dateKey) } catch (_: Exception) { dateError = true; return@TextButton }
                val bedMs = shiftParseTime(dateKey, bedTime) ?: return@TextButton
                val wakeMs = shiftParseTime(dateKey, wakeTime, afterMs = bedMs) ?: return@TextButton
                // dateIso = the wake date (the morning you woke up)
                onSave(SleepLogEntry(dateIso = dateKeyFromMs(wakeMs), bedMillis = bedMs, wakeMillis = wakeMs))
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun shiftFormatMs(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

private fun shiftElapsed(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun shiftDateLabel(dateKey: String): String = try {
    LocalDate.parse(dateKey).format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
} catch (_: Exception) { dateKey }

private fun dateKeyFromMs(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

private fun shiftParseTime(dateKey: String, timeStr: String, afterMs: Long? = null): Long? {
    val time = ShiftTime.parse(timeStr) ?: return null
    val date = try { LocalDate.parse(dateKey) } catch (_: Exception) { return null }
    val cal = Calendar.getInstance().apply {
        set(Calendar.YEAR, date.year)
        set(Calendar.MONTH, date.monthValue - 1)
        set(Calendar.DAY_OF_MONTH, date.dayOfMonth)
        set(Calendar.HOUR_OF_DAY, time.hour)
        set(Calendar.MINUTE, time.minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (afterMs != null && cal.timeInMillis <= afterMs) cal.add(Calendar.DAY_OF_YEAR, 1)
    return cal.timeInMillis
}
