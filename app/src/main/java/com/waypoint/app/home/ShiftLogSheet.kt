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
    var sessions by remember { mutableStateOf(ws.getRecentSessions(30)) }
    var calEvents by remember { mutableStateOf<List<Pair<Long, CalendarEvent>>>(emptyList()) }
    var editTarget by remember { mutableStateOf<Pair<String, ShiftSession>?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    // Orphan calendar events: created by the app but no longer linked to any session
    val linkedIds by remember(sessions) {
        derivedStateOf { sessions.mapNotNull { it.second.calendarEventId }.toSet() }
    }
    val orphanEvents by remember(calEvents, linkedIds) {
        derivedStateOf { calEvents.filter { (id, _) -> id !in linkedIds } }
    }

    fun reloadSessions() { sessions = ws.getRecentSessions(30) }
    fun reloadCalendar() { scope.launch { calEvents = calendarSignals.queryWaypointEvents(30) } }
    fun reload() { reloadSessions(); reloadCalendar() }

    LaunchedEffect(Unit) { calEvents = calendarSignals.queryWaypointEvents(30) }

    val isEmpty = sessions.isEmpty() && orphanEvents.isEmpty()

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
                        "Shift Log",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    TextButton(onClick = { showAdd = true }) { Text("Add") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (isEmpty) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No logged shifts in the last 30 days",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
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

                        if (orphanEvents.isNotEmpty()) {
                            item {
                                LogSectionLabel("Orphan calendar events")
                            }
                            items(orphanEvents, key = { "c_${it.first}" }) { (id, event) ->
                                CalendarEventRow(
                                    event = event,
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
}

@Composable
private fun LogSectionLabel(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.4.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

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

@Composable
private fun CalendarEventRow(event: CalendarEvent, onDelete: () -> Unit) {
    val label = remember(event.startMillis) { shiftDateLabel(dateKeyFromMs(event.startMillis)) }
    val startStr = shiftFormatMs(event.startMillis)
    val endStr = shiftFormatMs(event.endMillis)
    val durStr = shiftElapsed(event.endMillis - event.startMillis)

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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
