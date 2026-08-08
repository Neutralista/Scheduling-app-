package com.waypoint.app.cycle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun CyclesTab(cycleTracker: CycleTracker) {
    var refreshKey by remember { mutableIntStateOf(0) }
    val cycles  = remember(refreshKey) { cycleTracker.store.loadAll() }
    val current = remember(refreshKey) { cycles.firstOrNull { it.isOpen } }
    val history = remember(refreshKey) { cycles.filter { !it.isOpen } }

    var editTarget by remember { mutableStateOf<Cycle?>(null) }

    // Tick every minute to refresh live durations
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(60_000L); tick++ }
    }

    Column(Modifier.fillMaxSize()) {

        // ── Header ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Cycles", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (current == null) {
                    TextButton(onClick = {
                        cycleTracker.manualStart()
                        refreshKey++
                    }) { Text("Start cycle") }
                }
                IconButton(onClick = { refreshKey++ }) {
                    Icon(
                        Icons.Filled.Refresh, contentDescription = "Refresh",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // Current cycle card
            if (current != null) {
                item(key = "current") {
                    CurrentCycleCard(cycle = current, tick = tick, onClick = { editTarget = current })
                }
                if (history.isNotEmpty()) {
                    item(key = "hist-label") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                            Text("History", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }

            // History cards
            items(history, key = { it.id }) { cycle ->
                CycleHistoryCard(cycle = cycle, onClick = { editTarget = cycle })
            }

            // Empty state
            if (cycles.isEmpty()) {
                item(key = "empty") {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No cycles logged yet.\n\nThe app detects when you wake up and fall asleep automatically. You can also tap \"Start cycle\" to begin one manually.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    // Edit sheet
    editTarget?.let { cycle ->
        CycleLogSheet(
            cycle = cycle,
            onDismiss = { editTarget = null },
            onSave = { updated ->
                cycleTracker.store.save(updated)
                refreshKey++
                editTarget = null
            },
            onDelete = {
                cycleTracker.store.delete(cycle.id)
                refreshKey++
                editTarget = null
            }
        )
    }
}

// ── Cards ─────────────────────────────────────────────────────────────────────

@Composable
private fun CurrentCycleCard(cycle: Cycle, tick: Int, onClick: () -> Unit) {
    val primary    = MaterialTheme.colorScheme.primary
    val tertiary   = MaterialTheme.colorScheme.tertiary
    val isSleeping = cycle.sleepStartMillis != null
    val now        = System.currentTimeMillis()
    val awakeMs    = remember(tick) { (cycle.sleepStartMillis ?: now) - cycle.wakeMillis }
    val sleepMs    = remember(tick) { if (isSleeping) now - cycle.sleepStartMillis!! else 0L }

    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (isSleeping) tertiary else primary)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isSleeping) "Sleeping" else "Awake",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSleeping) tertiary else primary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = Cycle.formatDate(cycle.wakeMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                LabeledTime("Wake", cycle.wakeMillis)
                if (isSleeping) LabeledTime("Sleep", cycle.sleepStartMillis!!, cycle.wakeMillis)
            }

            Text(
                text = if (isSleeping)
                    "Sleeping for ${Cycle.formatDuration(sleepMs)}  •  Awake ${Cycle.formatDuration(awakeMs)}"
                else
                    "Awake for ${Cycle.formatDuration(awakeMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Icon(
                    Icons.Filled.KeyboardArrowRight, contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CycleHistoryCard(cycle: Cycle, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Cycle.formatDate(cycle.wakeMillis),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Filled.KeyboardArrowRight, contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LabeledTime("Wake", cycle.wakeMillis)
                cycle.sleepStartMillis?.let { LabeledTime("Sleep", it, cycle.wakeMillis) }
                cycle.nextWakeMillis?.let   { LabeledTime("Next wake", it, cycle.wakeMillis) }
            }

            Spacer(Modifier.height(2.dp))
            CycleMiniBar(cycle)

            Text(
                text = buildString {
                    append("Total: ${Cycle.formatDuration(cycle.totalDurationMs)}")
                    if (cycle.awakeDurationMs > 0) append("  Awake: ${Cycle.formatDuration(cycle.awakeDurationMs)}")
                    cycle.sleepDurationMs?.let { append("  Sleep: ${Cycle.formatDuration(it)}") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LabeledTime(label: String, millis: Long, referenceMillis: Long? = null) {
    val crossesDate = referenceMillis != null &&
        Cycle.dateLabel(millis) != Cycle.dateLabel(referenceMillis)
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            Cycle.formatTime(millis),
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum"
            )
        )
        if (crossesDate) {
            Text(
                Cycle.formatDate(millis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun CycleMiniBar(cycle: Cycle) {
    val totalMs = cycle.totalDurationMs
    if (totalMs <= 0L) return

    val primary    = MaterialTheme.colorScheme.primary
    val sleepColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
    val trackColor = MaterialTheme.colorScheme.outlineVariant

    val awakeFrac = ((cycle.sleepStartMillis?.minus(cycle.wakeMillis)
        ?: cycle.totalDurationMs).toFloat() / totalMs).coerceIn(0f, 1f)
    val sleepFrac = ((cycle.sleepDurationMs ?: 0L).toFloat() / totalMs)
        .coerceIn(0f, (1f - awakeFrac).coerceAtLeast(0f))
    val restFrac  = (1f - awakeFrac - sleepFrac).coerceAtLeast(0f)

    Box(
        Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(trackColor)
    ) {
        Row(Modifier.fillMaxSize()) {
            if (awakeFrac > 0f) {
                Box(Modifier.weight(awakeFrac).fillMaxHeight().background(primary))
            }
            if (sleepFrac > 0f) {
                Box(Modifier.weight(sleepFrac).fillMaxHeight().background(sleepColor))
            }
            if (restFrac > 0f) {
                Box(Modifier.weight(restFrac).fillMaxHeight().background(trackColor))
            }
        }
    }
}
