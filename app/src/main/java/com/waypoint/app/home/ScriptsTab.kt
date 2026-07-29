package com.waypoint.app.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waypoint.app.script.AppScript
import com.waypoint.app.script.ScriptState
import com.waypoint.app.script.ScriptedModule
import com.waypoint.app.script.SettingSpec

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsTab(
    scripts: List<AppScript>,
    statesById: Map<String, ScriptState>,
    onStateChange: (String, ScriptState) -> Unit,
    onAddScript: (String) -> String?,
    onUpdateScript: (id: String, newSource: String) -> String?,
    onRemoveScript: (String) -> Unit,
    onResetScript: (String) -> Unit
) {
    var showAddSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(Modifier.fillMaxSize()) {
        if (scripts.isEmpty()) {
            ScriptsEmptyState(modifier = Modifier.align(Alignment.Center))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(scripts, key = { it.id }) { script ->
                    ScriptRow(
                        script = script,
                        state = statesById[script.id] ?: ScriptState(),
                        onStateChange = { onStateChange(script.id, it) },
                        onUpdate = onUpdateScript,
                        onRemove = { onRemoveScript(script.id) },
                        onReset = { onResetScript(script.id) }
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }

        FloatingActionButton(
            onClick = { showAddSheet = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add script")
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            AddScriptSheet(
                onDismiss = { showAddSheet = false },
                onLoad = onAddScript
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScriptRow(
    script: AppScript,
    state: ScriptState,
    onStateChange: (ScriptState) -> Unit,
    onUpdate: (id: String, newSource: String) -> String?,
    onRemove: () -> Unit,
    onReset: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showEditSheet by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = script.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 3.dp)
                ) {
                    if (script.replacesId != null) {
                        ScriptBadge("Override", isAccent = true)
                    } else {
                        ScriptBadge(if (script.isUserScript) "User" else "Built-in")
                    }
                    if (script.hasWidget) ScriptBadge("Widget")
                }
            }
            if (script.isUserScript) {
                androidx.compose.material3.IconButton(
                    onClick = { showEditSheet = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit code",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }

        // Expanded content
        AnimatedVisibility(visible = expanded) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Settings panel
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    script.SettingsContent()
                    val specs = remember(script.id, (script as? ScriptedModule)?.source) {
                        (script as? ScriptedModule)?.getSettings() ?: emptyList()
                    }
                    if (specs.isNotEmpty()) {
                        ScriptSettingsPanel(specs = specs, state = state, onStateChange = onStateChange)
                    }
                }

                // Action buttons
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (script.isUserScript) {
                        TextButton(onClick = { showRemoveDialog = true }) {
                            Text("Remove", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (script.canResetToDefaults) {
                        TextButton(onClick = { showResetDialog = true }) {
                            Text("Reset to defaults", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
    }

    // Edit sheet (user scripts only)
    if (showEditSheet) {
        val scriptedSource = remember(script.id) {
            // ScriptedModule exposes its source; for other AppScript types this is empty
            (script as? com.waypoint.app.script.ScriptedModule)?.source ?: ""
        }
        ModalBottomSheet(
            onDismissRequest = { showEditSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            AddScriptSheet(
                onDismiss = { showEditSheet = false },
                onLoad = { src -> onUpdate(script.id, src) },
                initialSource = scriptedSource,
                title = "Edit script",
                subtitle = script.displayName
            )
        }
    }

    // Remove confirmation dialog
    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text("Remove script") },
            text = { Text("Remove \"${script.displayName}\"? You can re-add it by pasting the code again.") },
            confirmButton = {
                TextButton(onClick = { onRemove(); showRemoveDialog = false }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Reset-to-defaults confirmation dialog
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset to defaults") },
            text = { Text("Reset \"${script.displayName}\" configuration to factory defaults? Your custom settings will be cleared.") },
            confirmButton = {
                TextButton(onClick = {
                    onReset()
                    showResetDialog = false
                }) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ScriptSettingsPanel(
    specs: List<SettingSpec>,
    state: ScriptState,
    onStateChange: (ScriptState) -> Unit
) {
    var prevSection: String? = null
    specs.forEach { spec ->
        if (spec.section != null && spec.section != prevSection) {
            prevSection = spec.section
            Text(
                text = spec.section,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
            )
        }
        val value = state.settings[spec.id] ?: spec.defaultValue
        when (spec.type) {
            "toggle" -> Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = spec.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = value.equals("true", ignoreCase = true),
                    onCheckedChange = { checked ->
                        onStateChange(state.copy(settings = state.settings + (spec.id to checked.toString())))
                    }
                )
            }
            else -> OutlinedTextField(
                value = value,
                onValueChange = { newVal ->
                    onStateChange(state.copy(settings = state.settings + (spec.id to newVal)))
                },
                label = { Text(spec.label) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    }
}

@Composable
private fun ScriptBadge(label: String, isAccent: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (isAccent) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (isAccent) MaterialTheme.colorScheme.onTertiaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun ScriptsEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No scripts yet",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "Tap + to add a script with JavaScript",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

