package com.waypoint.app.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waypoint.app.planner.DayTimelineView
import com.waypoint.app.planner.EventPlannerRegistry
import com.waypoint.app.script.AppScript
import com.waypoint.app.script.ScriptState
import com.waypoint.app.signal.CalendarSignals
import com.waypoint.app.signal.WorkScheduleSignals
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(
    workSchedule: WorkScheduleSignals,
    eventPlanner: EventPlannerRegistry,
    calendarSignals: CalendarSignals,
    scripts: List<AppScript>,
    statesById: Map<String, ScriptState>,
    onStateChange: (scriptId: String, newState: ScriptState) -> Unit,
    onAddScript: (String) -> String?,
    onUpdateScript: (id: String, newSource: String) -> String?,
    onRemoveScript: (String) -> Unit,
    onResetScript: (String) -> Unit,
    onPermissionGranted: () -> Unit,
    initialTab: Int = 0
) {
    var selectedTab by remember { mutableIntStateOf(initialTab) }

    val widgets = remember(scripts) { scripts.filter { it.hasWidget } }
    val widgetsDone = widgets.count { statesById[it.id]?.doneToday == true }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        AppHeader(
            scriptCount = scripts.size,
            widgetsDone = widgetsDone,
            widgetsTotal = widgets.size
        )
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                text = { Text("Plan", style = MaterialTheme.typography.labelMedium) })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                text = { Text("Scripts", style = MaterialTheme.typography.labelMedium) })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 },
                text = { Text("Widgets", style = MaterialTheme.typography.labelMedium) })
            Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 },
                text = { Text("Settings", style = MaterialTheme.typography.labelMedium) })
        }

        Box(Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> PlanTab(workSchedule = workSchedule, eventPlanner = eventPlanner, calendarSignals = calendarSignals)
                1 -> ScriptsTab(
                    scripts = scripts,
                    statesById = statesById,
                    onStateChange = onStateChange,
                    onAddScript = onAddScript,
                    onUpdateScript = onUpdateScript,
                    onRemoveScript = onRemoveScript,
                    onResetScript = { id -> onResetScript(id) }
                )
                2 -> WidgetsTab(
                    widgets = widgets,
                    statesById = statesById,
                    onStateChange = onStateChange
                )
                3 -> SettingsTab(onPermissionGranted = onPermissionGranted)
            }
        }
    }
}

// ── App header ────────────────────────────────────────────────────────────────

@Composable
private fun AppHeader(
    scriptCount: Int,
    widgetsDone: Int,
    widgetsTotal: Int
) {
    val primary = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.background
    val outline = MaterialTheme.colorScheme.outline

    val progressFraction = if (widgetsTotal > 0) widgetsDone.toFloat() / widgetsTotal else 0f
    val allDone = widgetsTotal > 0 && widgetsDone == widgetsTotal

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
                        widgetsTotal == 0 -> "$scriptCount scripts"
                        allDone -> "All done"
                        else -> "$widgetsDone of $widgetsTotal done"
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
private fun PlanTab(
    workSchedule: WorkScheduleSignals,
    eventPlanner: EventPlannerRegistry,
    calendarSignals: CalendarSignals
) {
    var dayOffset by remember { mutableIntStateOf(0) }
    val selectedDate = remember(dayOffset) { LocalDate.now().plusDays(dayOffset.toLong()) }
    val dateFmt = remember { DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { dayOffset-- }) {
                Icon(
                    Icons.Filled.KeyboardArrowLeft,
                    contentDescription = "Previous day",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(if (dayOffset != 0) Modifier.clickable { dayOffset = 0 } else Modifier)
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val label = when (dayOffset) {
                    -1 -> "Yesterday"
                    0  -> "Today"
                    1  -> "Tomorrow"
                    else -> selectedDate.format(dateFmt)
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (dayOffset == -1 || dayOffset == 1) {
                    Text(
                        text = selectedDate.format(dateFmt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (dayOffset != 0) {
                    Text(
                        text = "tap to return to today",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                }
            }
            IconButton(onClick = { dayOffset++ }) {
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = "Next day",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DayTimelineView(
            ws = workSchedule,
            registry = eventPlanner,
            calendarSignals = calendarSignals,
            date = selectedDate,
            modifier = Modifier.weight(1f)
        )
    }
}
