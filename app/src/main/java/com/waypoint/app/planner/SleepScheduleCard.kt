package com.waypoint.app.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.waypoint.app.signal.ShiftTime

@Composable
fun SleepScheduleCard(
    store: SleepScheduleStore,
    registry: EventPlannerRegistry,
    modifier: Modifier = Modifier
) {
    var schedule by remember { mutableStateOf(store.load()) }

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
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SleepTimeField(
                        label = "Wake",
                        value = schedule.wakeTime.displayString,
                        modifier = Modifier.weight(1f),
                        onCommit = { text ->
                            ShiftTime.parse(text)?.let { store.setWakeTime(it); commit() }
                        }
                    )
                    SleepTimeField(
                        label = "Bed",
                        value = schedule.bedTime.displayString,
                        modifier = Modifier.weight(1f),
                        onCommit = { text ->
                            ShiftTime.parse(text)?.let { store.setBedTime(it); commit() }
                        }
                    )
                }
                val totalH = schedule.totalSleepMinutes / 60
                val totalM = schedule.totalSleepMinutes % 60
                val summary = if (totalM == 0) "${totalH}h sleep" else "${totalH}h ${totalM}m sleep"
                Text(
                    text = "${schedule.bedTime.displayString} – ${schedule.wakeTime.displayString} · $summary",
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
    var text by remember(value) { mutableStateOf(value) }
    val focusManager = LocalFocusManager.current
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = {
                onCommit(text)
                focusManager.clearFocus()
            }),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
