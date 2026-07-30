package com.waypoint.app.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.waypoint.app.signal.ShiftTime
import com.waypoint.app.ui.components.TimePickerChip
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

@Composable
fun SleepLogCard(
    logStore: SleepLogStore,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var sleepState by remember { mutableStateOf(logStore.getSleepModeState()) }
    var todayEntry by remember { mutableStateOf(logStore.loadToday()) }
    var isEditing by remember { mutableStateOf(false) }

    val scheduledBedMs = remember { logStore.getScheduledBedMs() }
    val scheduledWakeMs = remember { logStore.getScheduledWakeMs() }

    var bedText by remember { mutableStateOf(scheduledBedMs?.let { epochMsToHHMM(it) } ?: "23:00") }
    var wakeText by remember { mutableStateOf(scheduledWakeMs?.let { epochMsToHHMM(it) } ?: "07:00") }

    LaunchedEffect(Unit) {
        val logged = logStore.recordPhoneActive()
        sleepState = logStore.getSleepModeState()
        if (logged) todayEntry = logStore.loadToday()
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
                    OutlinedButton(onClick = {
                        logStore.cancelSleepMode()
                        SleepCheckReceiver.cancel(context)
                        sleepState = SleepModeState.IDLE
                    }) { Text("Cancel") }
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
                    OutlinedButton(onClick = {
                        logStore.cancelSleepMode()
                        SleepCheckReceiver.cancel(context)
                        sleepState = SleepModeState.IDLE
                    }) { Text("Cancel") }
                }

                todayEntry != null && !isEditing -> {
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
                            logStore.clearToday()
                            todayEntry = null
                        }) { Text("Clear") }
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
                            logStore.logManual(bedMs, wakeMs)
                            todayEntry = logStore.loadToday()
                            isEditing = false
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
