package com.waypoint.app.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waypoint.app.planner.NamedBlock
import com.waypoint.app.planner.NamedBlockStore
import com.waypoint.app.script.AppScript
import com.waypoint.app.script.ScriptState

private val DAY_ABBREVS = mapOf(1 to "Mo", 2 to "Tu", 3 to "We", 4 to "Th", 5 to "Fr", 6 to "Sa", 7 to "Su")

@Composable
fun WidgetsTab(
    widgets: List<AppScript>,
    statesById: Map<String, ScriptState>,
    onStateChange: (scriptId: String, newState: ScriptState) -> Unit
) {
    val context = LocalContext.current
    val namedBlockStore = remember { NamedBlockStore(context) }
    var blockRefreshKey by remember { mutableIntStateOf(0) }
    val allBlocks by remember(blockRefreshKey) { mutableStateOf(namedBlockStore.loadAllBlocks()) }
    var showAddBlock by remember { mutableStateOf(false) }
    var editBlock by remember { mutableStateOf<NamedBlock?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Named Blocks section ─────────────────────────────────────────────
        item(key = "blocks_header") {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Time Blocks",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showAddBlock = true }) { Text("+ Add") }
            }
        }

        if (allBlocks.isEmpty()) {
            item(key = "blocks_empty") {
                Text(
                    "No time blocks yet. Add recurring events like Gym, Work, or Dance lessons — " +
                    "then attach tasks that happen before, during, or after them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        } else {
            items(allBlocks, key = { "block_${it.id}" }) { block ->
                NamedBlockRow(
                    block = block,
                    onEdit = { editBlock = block },
                    onDelete = {
                        namedBlockStore.deleteBlock(block.id)
                        blockRefreshKey++
                    }
                )
            }
        }

        item(key = "divider") {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        // ── Modules / Widgets section ────────────────────────────────────────
        item(key = "modules_header") {
            Text(
                "Modules",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        if (widgets.isEmpty()) {
            item(key = "modules_empty") {
                Text(
                    "Scripts that declare a widget will appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(widgets, key = { it.id }) { script ->
                script.WidgetContent(
                    state = statesById[script.id],
                    onStateChange = { newState -> onStateChange(script.id, newState) }
                )
            }
        }

        item(key = "bottom_spacer") { Spacer(Modifier.height(16.dp)) }
    }

    // ── Sheets ───────────────────────────────────────────────────────────────
    if (showAddBlock || editBlock != null) {
        NamedBlockSheet(
            initial = editBlock,
            store = namedBlockStore,
            onDismiss = { showAddBlock = false; editBlock = null },
            onSaved = {
                blockRefreshKey++
                showAddBlock = false
                editBlock = null
            }
        )
    }
}

@Composable
private fun NamedBlockRow(
    block: NamedBlock,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = block.colorArgb?.let { Color(it) } ?: Color(0xFF4DB6AC)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 10.dp)
        ) {
            Text(block.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            val durLabel = when {
                block.estimatedMinutes < 60 -> "${block.estimatedMinutes}m"
                block.estimatedMinutes % 60 == 0 -> "${block.estimatedMinutes / 60}h"
                else -> "${block.estimatedMinutes / 60}h ${block.estimatedMinutes % 60}m"
            }
            val daysLabel = if (block.recurringDays.isEmpty()) "No recurring days"
                else block.recurringDays.sorted().mapNotNull { DAY_ABBREVS[it] }.joinToString(" ")
            Text(
                "$durLabel · $daysLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Edit, "Edit block", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Delete, "Delete block", modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error)
        }
    }
}
