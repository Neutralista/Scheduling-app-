package com.waypoint.app.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.waypoint.app.planner.BlockTask
import com.waypoint.app.planner.BlockTaskPlacement
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlockTaskSheet(
    blockId: String,
    initial: BlockTask? = null,
    onDismiss: () -> Unit,
    onSave: (BlockTask) -> Unit
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var titleError by remember { mutableStateOf(false) }

    val durationPresets = listOf(5, 10, 15, 30, 45, 60)
    val initialDur = initial?.durationMinutes ?: 15
    var selectedDuration by remember { mutableIntStateOf(if (initialDur in durationPresets) initialDur else 0) }
    var customDurationText by remember { mutableStateOf(if (initialDur !in durationPresets) initialDur.toString() else "") }

    var placement by remember { mutableStateOf(initial?.placement ?: BlockTaskPlacement.BEFORE) }
    var priority by remember { mutableIntStateOf(initial?.priority ?: 5) }
    var isAlways by remember { mutableStateOf(initial?.isAlways ?: true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true)
    ) {
        Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // Top bar
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Text(
                        if (initial == null) "Add Task" else "Edit Task",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    TextButton(onClick = {
                        titleError = title.isBlank()
                        if (titleError) return@TextButton
                        val dur = if (selectedDuration > 0) selectedDuration
                            else customDurationText.toIntOrNull()?.takeIf { it > 0 } ?: -1
                        if (dur <= 0) { titleError = true; return@TextButton }
                        onSave(BlockTask(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            blockId = blockId,
                            title = title.trim(),
                            durationMinutes = dur,
                            placement = placement,
                            priority = priority,
                            isAlways = isAlways
                        ))
                    }) { Text("Save") }
                }
                HorizontalDivider()

                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Name
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Task name", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = title, onValueChange = { title = it; titleError = false },
                            modifier = Modifier.fillMaxWidth(),
                            isError = titleError,
                            supportingText = if (titleError) ({ Text("Required") }) else null,
                            singleLine = true
                        )
                    }

                    // Duration
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Duration", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            durationPresets.forEach { min ->
                                val label = if (min < 60) "${min}m" else "${min / 60}h"
                                FilterChip(
                                    selected = selectedDuration == min,
                                    onClick = { selectedDuration = min; customDurationText = "" },
                                    label = { Text(label) }
                                )
                            }
                            FilterChip(
                                selected = selectedDuration == 0,
                                onClick = { selectedDuration = 0 },
                                label = { Text("Other") }
                            )
                        }
                        if (selectedDuration == 0) {
                            OutlinedTextField(
                                value = customDurationText,
                                onValueChange = { customDurationText = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Minutes") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true
                            )
                        }
                    }

                    // Placement
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Placement", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BlockTaskPlacement.entries.forEach { p ->
                                FilterChip(
                                    selected = placement == p,
                                    onClick = { placement = p },
                                    label = { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) }
                                )
                            }
                        }
                    }

                    // Priority
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Priority", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Low" to 3, "Medium" to 5, "High" to 7, "Critical" to 9).forEach { (lbl, v) ->
                                FilterChip(selected = priority == v, onClick = { priority = v }, label = { Text(lbl) })
                            }
                        }
                    }

                    // Recurrence
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Recurrence", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = isAlways, onClick = { isAlways = true }, label = { Text("Always") })
                            FilterChip(selected = !isAlways, onClick = { isAlways = false }, label = { Text("Situational") })
                        }
                        Text(
                            if (isAlways) "Scheduled every time this block occurs."
                            else "Toggle on/off per occurrence from the block view.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
