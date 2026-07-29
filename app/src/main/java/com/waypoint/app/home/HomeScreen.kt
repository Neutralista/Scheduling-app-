package com.waypoint.app.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waypoint.app.planner.DayTimelineView
import com.waypoint.app.planner.EventPlannerRegistry
import com.waypoint.app.planner.SleepScheduleCard
import com.waypoint.app.planner.SleepScheduleStore
import com.waypoint.app.signal.WorkScheduleSignals
import com.waypoint.app.widget.HabitWidget
import com.waypoint.app.widget.ScriptedWidget
import com.waypoint.app.widget.WidgetSize
import com.waypoint.app.widget.WidgetState
import com.waypoint.app.widget.WorkScheduleCard
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    workSchedule: WorkScheduleSignals,
    eventPlanner: EventPlannerRegistry,
    sleepStore: SleepScheduleStore,
    addedWidgets: List<HabitWidget>,
    statesById: Map<String, WidgetState>,
    onStateChange: (widgetId: String, newState: WidgetState) -> Unit,
    onAddWidget: (source: String) -> String?,
    onRemoveWidget: (id: String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        AppHeader(addedWidgets = addedWidgets, statesById = statesById)
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                text = { Text("Plan", style = MaterialTheme.typography.labelMedium) })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                text = { Text("Habits", style = MaterialTheme.typography.labelMedium) })
        }

        Box(Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> PlanTab(workSchedule = workSchedule, eventPlanner = eventPlanner)
                1 -> HabitsTab(
                    workSchedule = workSchedule,
                    eventPlanner = eventPlanner,
                    sleepStore = sleepStore,
                    addedWidgets = addedWidgets,
                    statesById = statesById,
                    onStateChange = onStateChange,
                    onRemoveWidget = onRemoveWidget
                )
            }

            // FAB only visible on Habits tab
            if (selectedTab == 1) {
                FloatingActionButton(
                    onClick = { showAddSheet = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = 20.dp)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add widget")
                }
            }
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            AddWidgetSheet(
                onDismiss = { showAddSheet = false },
                onLoad = onAddWidget
            )
        }
    }
}

// ── App header ────────────────────────────────────────────────────────────────

@Composable
private fun AppHeader(
    addedWidgets: List<HabitWidget>,
    statesById: Map<String, WidgetState>
) {
    val primary = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.background
    val outline = MaterialTheme.colorScheme.outline

    val doneCount = addedWidgets.count { statesById[it.id]?.doneToday == true }
    val totalCount = addedWidgets.size
    val progressFraction = if (totalCount > 0) doneCount.toFloat() / totalCount else 0f
    val allDone = totalCount > 0 && doneCount == totalCount

    val dateFormatter = remember {
        DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.getDefault())
    }
    var dateText by remember { mutableStateOf(LocalDate.now().format(dateFormatter)) }
    LaunchedEffect(Unit) {
        while (true) { delay(60_000L); dateText = LocalDate.now().format(dateFormatter) }
    }

    Box(Modifier.fillMaxWidth().background(bg)) {
        Canvas(Modifier.fillMaxWidth().height(100.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primary.copy(alpha = 0.09f), Color.Transparent),
                    center = Offset(size.width / 2f, -20.dp.toPx()),
                    radius = 220.dp.toPx()
                ),
                radius = 220.dp.toPx(),
                center = Offset(size.width / 2f, -20.dp.toPx())
            )
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                text = "WAYPOINT",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                ),
                color = primary.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Today",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = dateText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    Modifier.weight(1f).height(4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(outline)
                ) {
                    if (progressFraction > 0f) {
                        Box(
                            Modifier.fillMaxWidth(progressFraction).height(4.dp)
                                .background(primary)
                        )
                    }
                }
                Text(
                    text = when {
                        totalCount == 0 -> "—"
                        allDone -> "All done"
                        else -> "$doneCount of $totalCount done"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter), color = outline)
    }
}

// ── Plan tab ──────────────────────────────────────────────────────────────────

@Composable
private fun PlanTab(workSchedule: WorkScheduleSignals, eventPlanner: EventPlannerRegistry) {
    Column(Modifier.fillMaxSize()) {
        DayTimelineView(ws = workSchedule, registry = eventPlanner, modifier = Modifier.weight(1f))
    }
}

// ── Habits tab ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HabitsTab(
    workSchedule: WorkScheduleSignals,
    eventPlanner: EventPlannerRegistry,
    sleepStore: SleepScheduleStore,
    addedWidgets: List<HabitWidget>,
    statesById: Map<String, WidgetState>,
    onStateChange: (widgetId: String, newState: WidgetState) -> Unit,
    onRemoveWidget: (id: String) -> Unit
) {
    var sleepCardRefreshKey by remember { mutableIntStateOf(0) }
    var removeConfirmId by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            WorkScheduleCard(
                ws = workSchedule,
                onShiftEnd = {
                    sleepStore.syncToRegistry(eventPlanner, workSchedule)
                    sleepCardRefreshKey++
                }
            )
        }
        item {
            SleepScheduleCard(
                store = sleepStore, registry = eventPlanner,
                ws = workSchedule, refreshKey = sleepCardRefreshKey
            )
        }

        if (addedWidgets.isEmpty()) {
            item { EmptyState() }
        } else {
            items(addedWidgets, key = { it.id }) { widget ->
                val isScripted = widget is ScriptedWidget
                val baseModifier = when (widget.uiConfig.size) {
                    WidgetSize.SMALL_TILE -> Modifier.padding(4.dp)
                    WidgetSize.WIDE_ROW   -> Modifier.fillMaxWidth()
                    WidgetSize.FULL_CARD  -> Modifier.fillMaxWidth().padding(vertical = 4.dp)
                }
                // Long-press on scripted widgets to remove them
                val modifier = if (isScripted) {
                    baseModifier.combinedClickable(
                        onClick = {},
                        onLongClick = { removeConfirmId = widget.id }
                    )
                } else baseModifier

                Box(modifier = modifier) {
                    widget.Content(
                        state = statesById[widget.id],
                        onStateChange = { newState -> onStateChange(widget.id, newState) }
                    )
                }
            }
        }
    }

    // Remove confirmation dialog
    if (removeConfirmId != null) {
        val id = removeConfirmId!!
        val name = addedWidgets.find { it.id == id }?.displayName ?: id
        AlertDialog(
            onDismissRequest = { removeConfirmId = null },
            title = { Text("Remove widget") },
            text = { Text("Remove \"$name\"? This can't be undone from the app — you'll need to paste the code again.") },
            confirmButton = {
                TextButton(onClick = { onRemoveWidget(id); removeConfirmId = null }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { removeConfirmId = null }) { Text("Cancel") }
            }
        )
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No habits yet",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Tap + to add a widget with JavaScript",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
