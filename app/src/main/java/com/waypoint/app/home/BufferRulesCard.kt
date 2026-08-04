package com.waypoint.app.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.waypoint.app.planner.AnchorType
import com.waypoint.app.planner.BufferRule
import com.waypoint.app.planner.BufferRulesStore
import java.util.UUID

private fun AnchorType.displayName(): String = when (this) {
    AnchorType.BEFORE_SHIFT_START -> "Before shift"
    AnchorType.AFTER_SHIFT_END    -> "After shift"
    AnchorType.BEFORE_SLEEP       -> "Before sleep"
    AnchorType.AFTER_WAKE         -> "After wake"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BufferRulesCard(store: BufferRulesStore, onChanged: () -> Unit = {}) {
    var rules by remember { mutableStateOf(store.loadAll()) }
    var showAdd by remember { mutableStateOf(false) }

    fun reload() { rules = store.loadAll(); onChanged() }

    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Buffers",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            IconButton(onClick = { showAdd = !showAdd }) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add buffer rule",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Text(
            "Reserve fixed time blocks around anchors like shift start/end or sleep.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )

        Spacer(Modifier.height(8.dp))

        if (rules.isEmpty() && !showAdd) {
            Text(
                "No buffer rules. Tap + to add one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }

        rules.forEach { rule ->
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { enabled ->
                        store.setEnabled(rule.id, enabled)
                        reload()
                    }
                )
                Column(Modifier.weight(1f)) {
                    Text(rule.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${rule.anchorType.displayName()} · ${rule.durationMinutes}m" +
                            if (rule.offsetMinutes > 0) " · offset ${rule.offsetMinutes}m" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                IconButton(
                    onClick = { store.delete(rule.id); reload() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Delete rule",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }

        if (showAdd) {
            Spacer(Modifier.height(12.dp))
            AddBufferRuleForm(
                onSave = { rule ->
                    store.save(rule)
                    reload()
                    showAdd = false
                },
                onCancel = { showAdd = false }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddBufferRuleForm(
    onSave: (BufferRule) -> Unit,
    onCancel: () -> Unit
) {
    var label by remember { mutableStateOf("") }
    var anchorType by remember { mutableStateOf(AnchorType.BEFORE_SLEEP) }
    var durationText by remember { mutableStateOf("15") }
    var offsetText by remember { mutableStateOf("0") }
    var labelError by remember { mutableStateOf(false) }

    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "New Buffer Rule",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            OutlinedTextField(
                value = label,
                onValueChange = { label = it; labelError = false },
                label = { Text("Label (e.g. Wind-down)") },
                isError = labelError,
                supportingText = if (labelError) ({ Text("Label is required") }) else null,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Text(
                "Anchor",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AnchorType.entries.forEach { at ->
                    FilterChip(
                        selected = anchorType == at,
                        onClick  = { anchorType = at },
                        label    = { Text(at.displayName(), style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { durationText = it },
                    label = { Text("Duration (min)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = offsetText,
                    onValueChange = { offsetText = it },
                    label = { Text("Offset (min)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Text(
                when (anchorType) {
                    AnchorType.BEFORE_SHIFT_START ->
                        "Reserves time ending at shift start (minus offset)."
                    AnchorType.AFTER_SHIFT_END ->
                        "Reserves time starting at shift end (plus offset)."
                    AnchorType.BEFORE_SLEEP ->
                        "Reserves time ending at scheduled bed time (minus offset)."
                    AnchorType.AFTER_WAKE ->
                        "Reserves time starting at scheduled wake time (plus offset)."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    labelError = label.trim().isEmpty()
                    if (labelError) return@TextButton
                    val dur = durationText.toIntOrNull()?.coerceAtLeast(1) ?: 15
                    val off = offsetText.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    onSave(
                        BufferRule(
                            id = UUID.randomUUID().toString(),
                            label = label.trim(),
                            anchorType = anchorType,
                            durationMinutes = dur,
                            offsetMinutes = off,
                            enabled = true
                        )
                    )
                }) {
                    Text("Add", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
