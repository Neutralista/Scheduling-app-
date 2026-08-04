package com.waypoint.app.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.waypoint.app.signal.ShiftTime
import com.waypoint.app.ui.components.TimePickerChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

@Composable
fun SleepScheduleCard(
    store: SleepScheduleStore,
    registry: EventPlannerRegistry,
    refreshKey: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logStore = remember { SleepLogStore(context) }

    var schedule by remember { mutableStateOf(store.load()) }
    val effective = remember(schedule, refreshKey) { store.computeEffectiveTimes(registry) }

    var sleepState by remember { mutableStateOf(logStore.getSleepModeState()) }
    var scheduledBedMs by remember { mutableStateOf(logStore.getScheduledBedMs()) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Restore rescheduled display state from the persisted flag so it survives tab navigation
    val initialRescheduled = remember {
        if (logStore.isRescheduledToday() && logStore.getSleepModeState() == SleepModeState.IDLE)
            logStore.getScheduledBedMs() to logStore.getScheduledWakeMs()
        else null to null
    }
    var rescheduledBedMs by remember { mutableStateOf(initialRescheduled.first) }
    var rescheduledWakeMs by remember { mutableStateOf(initialRescheduled.second) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowMs = System.currentTimeMillis()
            sleepState = logStore.getSleepModeState()
            scheduledBedMs = logStore.getScheduledBedMs()
        }
    }

    val bedMs = scheduledBedMs
    val isOverdue = schedule.enabled &&
            sleepState == SleepModeState.IDLE &&
            bedMs != null &&
            nowMs > bedMs &&
            rescheduledBedMs == null

    fun commit() {
        store.syncToRegistry(registry)
        schedule = store.load()
        rescheduledBedMs = null
        rescheduledWakeMs = null
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sleep", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = schedule.enabled,
                    onCheckedChange = { store.setEnabled(it); commit() }
                )
            }

            if (schedule.enabled) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Preferred times",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SleepTimeField(
                        label = "Wake",
                        value = schedule.preferredWakeTime.displayString,
                        modifier = Modifier.weight(1f),
                        onCommit = { text ->
                            ShiftTime.parse(text)?.let { store.setPreferredWakeTime(it); commit() }
                        }
                    )
                    SleepTimeField(
                        label = "Bed",
                        value = schedule.preferredBedTime.displayString,
                        modifier = Modifier.weight(1f),
                        onCommit = { text ->
                            ShiftTime.parse(text)?.let { store.setPreferredBedTime(it); commit() }
                        }
                    )
                }

                // Summary line — shows rescheduled times when overridden, computed otherwise
                val (summaryBed, summaryWake, sleepMins) = if (rescheduledBedMs != null && rescheduledWakeMs != null) {
                    val rb = rescheduledBedMs!!
                    val rw = rescheduledWakeMs!!
                    val mins = ((rw - rb) / 60_000L).toInt().coerceAtLeast(0)
                    Triple(formatSleepMs(rb), formatSleepMs(rw), mins)
                } else {
                    val mins = effective.totalSleepMinutes
                    Triple(effective.bedTime.displayString, effective.wakeTime.displayString, mins)
                }
                val totalH = sleepMins / 60
                val totalM = sleepMins % 60
                val sleepSummary = if (totalM == 0) "${totalH}h sleep" else "${totalH}h ${totalM}m sleep"
                val rescheduledSuffix = if (rescheduledBedMs != null) " · rescheduled" else ""
                Text(
                    text = "$summaryBed – $summaryWake · $sleepSummary$rescheduledSuffix",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )

                // Reschedule row — shown when user is awake past bedtime
                if (isOverdue && bedMs != null) {
                    val minutesLate = ((nowMs - bedMs) / 60_000L).toInt().coerceAtLeast(1)
                    val lateText = if (minutesLate >= 60) {
                        val h = minutesLate / 60
                        val m = minutesLate % 60
                        if (m > 0) "${h}h ${m}m past bedtime" else "${h}h past bedtime"
                    } else "${minutesLate}m past bedtime"
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            lateText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.75f)
                        )
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val (newBed, newWake) = withContext(Dispatchers.IO) {
                                        store.rescheduleBedToNow(logStore, registry)
                                    }
                                    rescheduledBedMs = newBed
                                    rescheduledWakeMs = newWake
                                    sleepState = logStore.getSleepModeState()
                                    scheduledBedMs = logStore.getScheduledBedMs()
                                }
                            }
                        ) {
                            Text(
                                "Delay bedtime",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Target sleep",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                if (schedule.targetSleepMinutes > 240) {
                                    store.setTargetSleepMinutes(schedule.targetSleepMinutes - 30)
                                    commit()
                                }
                            },
                            enabled = schedule.targetSleepMinutes > 240
                        ) { Text("−") }
                        val th = schedule.targetSleepMinutes / 60
                        val tm = schedule.targetSleepMinutes % 60
                        Text(
                            if (tm == 0) "${th}h" else "${th}h ${tm}m",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(
                            onClick = {
                                if (schedule.targetSleepMinutes < 720) {
                                    store.setTargetSleepMinutes(schedule.targetSleepMinutes + 30)
                                    commit()
                                }
                            },
                            enabled = schedule.targetSleepMinutes < 720
                        ) { Text("+") }
                    }
                }
            }
        }
    }
}

private fun formatSleepMs(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

@Composable
private fun SleepTimeField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onCommit: (String) -> Unit
) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        TimePickerChip(value = value, onValueChange = onCommit, modifier = Modifier.fillMaxWidth())
    }
}
