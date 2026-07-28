package com.waypoint.app.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waypoint.app.planner.DayTimelineView
import com.waypoint.app.planner.EventPlannerRegistry
import com.waypoint.app.planner.SleepScheduleCard
import com.waypoint.app.planner.SleepScheduleStore
import com.waypoint.app.signal.WorkScheduleSignals
import com.waypoint.app.widget.HabitWidget
import com.waypoint.app.widget.WidgetSize
import com.waypoint.app.widget.WidgetState
import com.waypoint.app.widget.WorkScheduleCard

@Composable
fun HomeScreen(
    workSchedule: WorkScheduleSignals,
    eventPlanner: EventPlannerRegistry,
    sleepStore: SleepScheduleStore,
    addedWidgets: List<HabitWidget>,
    statesById: Map<String, WidgetState>,
    onStateChange: (widgetId: String, newState: WidgetState) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Plan", style = MaterialTheme.typography.labelMedium) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Habits", style = MaterialTheme.typography.labelMedium) }
            )
        }

        when (selectedTab) {
            0 -> PlanTab(workSchedule = workSchedule, eventPlanner = eventPlanner)
            1 -> HabitsTab(
                workSchedule = workSchedule,
                eventPlanner = eventPlanner,
                sleepStore = sleepStore,
                addedWidgets = addedWidgets,
                statesById = statesById,
                onStateChange = onStateChange
            )
        }
    }
}

@Composable
private fun PlanTab(
    workSchedule: WorkScheduleSignals,
    eventPlanner: EventPlannerRegistry
) {
    Column(Modifier.fillMaxSize()) {
        DayTimelineView(
            ws = workSchedule,
            registry = eventPlanner,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HabitsTab(
    workSchedule: WorkScheduleSignals,
    eventPlanner: EventPlannerRegistry,
    sleepStore: SleepScheduleStore,
    addedWidgets: List<HabitWidget>,
    statesById: Map<String, WidgetState>,
    onStateChange: (widgetId: String, newState: WidgetState) -> Unit
) {
    var sleepCardRefreshKey by remember { mutableIntStateOf(0) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
        item { SleepScheduleCard(store = sleepStore, registry = eventPlanner, ws = workSchedule, refreshKey = sleepCardRefreshKey) }

        if (addedWidgets.isEmpty()) {
            item { EmptyState() }
        } else {
            items(addedWidgets, key = { it.id }) { widget ->
                val modifier = when (widget.uiConfig.size) {
                    WidgetSize.SMALL_TILE -> Modifier.padding(4.dp)
                    WidgetSize.WIDE_ROW   -> Modifier.fillMaxWidth()
                    WidgetSize.FULL_CARD  -> Modifier.fillMaxWidth().padding(vertical = 4.dp)
                }
                Box(modifier = modifier) {
                    widget.Content(
                        state = statesById[widget.id],
                        onStateChange = { newState -> onStateChange(widget.id, newState) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No habits yet",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Add a habit by implementing HabitWidget\nand registering it in WaypointApplication.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
