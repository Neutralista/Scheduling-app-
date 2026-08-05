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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.waypoint.app.signal.ShiftTime
import com.waypoint.app.ui.components.TimePickerChip

@Composable
fun SleepScheduleCard(
    store: SleepScheduleStore,
    registry: EventPlannerRegistry,
    refreshKey: Int = 0,
    modifier: Modifier = Modifier
) {
    var schedule by remember { mutableStateOf(store.load()) }
    val effective = remember(schedule, refreshKey) { store.computeEffectiveTimes(registry) }

    fun commit() {
        store.syncToRegistry(registry)
        schedule = store.load()
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

                // Summary line
                val sleepMins = effective.totalSleepMinutes
                val totalH = sleepMins / 60
                val totalM = sleepMins % 60
                val sleepSummary = if (totalM == 0) "${totalH}h sleep" else "${totalH}h ${totalM}m sleep"
                Text(
                    text = "${effective.bedTime.displayString} – ${effective.wakeTime.displayString} · $sleepSummary",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )

            }
        }
    }
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
