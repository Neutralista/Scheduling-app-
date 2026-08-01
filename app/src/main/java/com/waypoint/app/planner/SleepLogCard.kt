package com.waypoint.app.planner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.waypoint.app.signal.ShiftTime
import com.waypoint.app.ui.components.TimePickerChip
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import kotlinx.coroutines.launch

@Composable
fun SleepLogCard(
    logStore: SleepLogStore,
    onSleepLogged: suspend () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sleepState by remember { mutableStateOf(logStore.getSleepModeState()) }
    var todayEntry by remember { mutableStateOf(logStore.loadToday()) }
    var isEditing by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    var calendarError by remember { mutableStateOf(false) }

    val scheduledBedMs = remember { logStore.getScheduledBedMs() }
    val scheduledWakeMs = remember { logStore.getScheduledWakeMs() }

    var bedText by remember { mutableStateOf(scheduledBedMs?.let { epochMsToHHMM(it) } ?: "23:00") }
    var wakeText by remember { mutableStateOf(scheduledWakeMs?.let { epochMsToHHMM(it) } ?: "07:00") }

    // Detect phone-active when the card composes (app opened while SLEEPING)
    LaunchedEffect(Unit) {
        val logged = logStore.recordPhoneActive()
        sleepState = logStore.getSleepModeState()
        if (logged) {
            todayEntry = logStore.loadToday()
            val entry = todayEntry
            if (entry != null) {
                SleepCalendarSync.write(context, logStore, entry.bedMillis, entry.wakeMillis, null)
                todayEntry = logStore.loadToday()
            }
            onSleepLogged()
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Sleep Log", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            when {
                sleepState == SleepModeState.MONITORING -> {
                    val sinceText = logStore.getSleepModeStartMillis()
                        ?.let { epochMsToHHMM(it) } ?: "--:--"
                    Text(
                        "Sleep mode active · monitoring since $sinceText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            logStore.cancelSleepMode()
                            SleepCheckReceiver.cancel(context)
                            sleepState = SleepModeState.IDLE
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) { Text("Cancel") }
                }

                sleepState == SleepModeState.SLEEPING -> {
                    val startText = logStore.getSleepStartMillis()
                        ?.let { epochMsToHHMM(it) } ?: "--:--"
                    Text(
                        "Sleep detected · started $startText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            // Sleep was detected — log the session before resetting state
                            val sleepStart = logStore.getSleepStartMillis()
                            val now = System.currentTimeMillis()
                            if (sleepStart != null && now > sleepStart) {
                                val oldEventId = todayEntry?.calendarEventId
                                logStore.logManual(sleepStart, now)
                                todayEntry = logStore.loadToday()
                                scope.launch {
                                    val entry = todayEntry
                                    if (entry != null) {
                                        SleepCalendarSync.write(context, logStore, entry.bedMillis, entry.wakeMillis, oldEventId)
                                        todayEntry = logStore.loadToday()
                                    }
                                    onSleepLogged()
                                }
                            }
                            logStore.cancelSleepMode()
                            SleepCheckReceiver.cancel(context)
                            sleepState = SleepModeState.IDLE
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) { Text("Done") }
                }

                todayEntry != null && !isEditing && !dismissed -> {
                    val entry = todayEntry!!
                    val bedStr = epochMsToHHMM(entry.bedMillis)
                    val wakeStr = epochMsToHHMM(entry.wakeMillis)
                    val durMins = ((entry.wakeMillis - entry.bedMillis) / 60_000L).toInt()
                    val durText = if (durMins > 0) {
                        val h = durMins / 60; val m = durMins % 60
                        if (m == 0) "${h}h" else "${h}h ${m}m"
                    } else "?"
                    Text(
                        "Logged: $bedStr – $wakeStr · $durText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            bedText = epochMsToHHMM(entry.bedMillis)
                            wakeText = epochMsToHHMM(entry.wakeMillis)
                            isEditing = true
                        }) { Text("Edit") }
                        TextButton(onClick = {
                            scope.launch {
                                val entry = todayEntry
                                if (entry != null) {
                                    val saved = SleepCalendarSync.write(
                                        context, logStore,
                                        entry.bedMillis, entry.wakeMillis,
                                        entry.calendarEventId
                                    )
                                    if (!saved) {
                                        calendarError = true
                                        return@launch
                                    }
                                    todayEntry = logStore.loadToday()
                                }
                                calendarError = false
                                dismissed = true
                            }
                        }) { Text("Save & Clear") }
                        if (calendarError) {
                            Text(
                                "Calendar not connected — go to Settings to grant Calendar access",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                else -> {
                    Button(
                        onClick = {
                            logStore.enterSleepMode()
                            SleepCheckReceiver.scheduleNextCheck(context)
                            sleepState = SleepModeState.MONITORING
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Start Sleep Mode") }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Or log manually:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Bed",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            TimePickerChip(
                                value = bedText,
                                onValueChange = { bedText = it },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Wake",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            TimePickerChip(
                                value = wakeText,
                                onValueChange = { wakeText = it },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val bed = ShiftTime.parse(bedText) ?: return@Button
                            val wake = ShiftTime.parse(wakeText) ?: return@Button
                            val zone = ZoneId.systemDefault()
                            val today = LocalDate.now()
                            val wakeMs = today.atTime(wake.hour, wake.minute)
                                .atZone(zone).toInstant().toEpochMilli()
                            val bedMs = if (bed.totalMinutes > wake.totalMinutes) {
                                today.minusDays(1).atTime(bed.hour, bed.minute)
                                    .atZone(zone).toInstant().toEpochMilli()
                            } else {
                                today.atTime(bed.hour, bed.minute)
                                    .atZone(zone).toInstant().toEpochMilli()
                            }
                            val durationMs = wakeMs - bedMs
                            if (durationMs < 60_000L || durationMs > 16 * 3600_000L) return@Button
                            val oldEventId = todayEntry?.calendarEventId
                            logStore.logManual(bedMs, wakeMs)
                            todayEntry = logStore.loadToday()
                            isEditing = false
                            scope.launch {
                                SleepCalendarSync.write(context, logStore, bedMs, wakeMs, oldEventId)
                                todayEntry = logStore.loadToday()
                                onSleepLogged()
                            }
                        }) { Text("Save") }
                        if (isEditing) {
                            TextButton(onClick = { isEditing = false }) { Text("Cancel") }
                        }
                    }
                }
            }
        }
    }
}

private fun epochMsToHHMM(ms: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}
