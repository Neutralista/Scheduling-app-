package com.waypoint.app.home

import android.content.Context
import android.provider.CalendarContract
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waypoint.app.alarm.AlarmSignals
import com.waypoint.app.cycle.CycleTracker
import com.waypoint.app.planner.BlockScopeView
import com.waypoint.app.planner.BlockSessionStore
import com.waypoint.app.planner.DayTimelineView
import com.waypoint.app.planner.EventPlannerRegistry
import com.waypoint.app.planner.NamedBlockStore
import com.waypoint.app.planner.SleepScheduleStore
import com.waypoint.app.script.AppScript
import com.waypoint.app.script.ScriptState
import com.waypoint.app.script.TaskManagerScript
import com.waypoint.app.signal.CalendarSignals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import com.waypoint.app.planner.BufferRulesStore
import com.waypoint.app.planner.CalendarPrefsStore
import com.waypoint.app.planner.ScheduledEvent
import com.waypoint.app.planner.TaskRequest
import com.waypoint.app.signal.CalendarEvent

@Composable
fun HomeScreen(
    eventPlanner: EventPlannerRegistry,
    taskManager: TaskManagerScript,
    calendarSignals: CalendarSignals,
    alarms: AlarmSignals,
    cycleTracker: CycleTracker,
    sleepTimesFlow: StateFlow<Pair<Long?, Long?>>,
    blockSessionStore: BlockSessionStore,
    blockSessionLogStore: com.waypoint.app.planner.BlockSessionLogStore? = null,
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
    val pagerState = rememberPagerState(initialPage = initialTab) { 7 }
    val scope = rememberCoroutineScope()
    var drawerOpen by remember { mutableStateOf(false) }
    val widgets = remember(scripts) { scripts.filter { it.hasWidget } }
    var headerRefreshKey by remember { mutableIntStateOf(0) }

    val headerPlan = remember(headerRefreshKey) { eventPlanner.planToday() }
    val plannerScheduled = remember(headerPlan) {
        headerPlan.scheduled.filter { it.event.sourceWidgetId == TaskManagerScript.WIDGET_ID }
    }
    val plannerDoneIds = remember(headerRefreshKey) { taskManager.completions.getDoneIds() }

    val tasksTotal = plannerScheduled.size
    val tasksDone = plannerScheduled.count { it.event.id in plannerDoneIds }

    val tabLabels = listOf("Plan", "History", "Tasks", "Blocks", "Modules", "Alarms", "Settings")

    val context = LocalContext.current
    val allNamedBlocks = remember { NamedBlockStore(context).loadAllBlocks() }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        AppHeader(
            tasksDone = tasksDone,
            tasksTotal = tasksTotal,
            onMenuClick = { drawerOpen = true }
        )
        ScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
            edgePadding = 0.dp
        ) {
            listOf("Plan", "History", "Tasks", "Blocks", "Modules", "Alarms", "Settings")
                .forEachIndexed { i, label ->
                    Tab(
                        selected = pagerState.currentPage == i,
                        onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                        text = { Text(label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            beyondViewportPageCount = 1
        ) { page ->
            when (page) {
                0 -> PlanTab(eventPlanner = eventPlanner, calendarSignals = calendarSignals, sleepTimesFlow = sleepTimesFlow, taskManager = taskManager, blockSessionStore = blockSessionStore, blockSessionLogStore = blockSessionLogStore, onHeaderRefresh = { headerRefreshKey++ })
                1 -> HistoryTab(cycleTracker = cycleTracker, taskManager = taskManager, blockSessionLogStore = blockSessionLogStore)
                2 -> TasksTab(registry = eventPlanner, taskManager = taskManager, calendarSignals = calendarSignals, blockSessionStore = blockSessionStore, availableBlocks = allNamedBlocks, onRefresh = { headerRefreshKey++ })
                3 -> BlocksTab(taskManager = taskManager)
                4 -> WidgetsTab(
                    widgets = widgets,
                    statesById = statesById,
                    onStateChange = onStateChange,
                    taskManager = taskManager
                )
                5 -> AlarmsTab(alarms = alarms, sleepTimesFlow = sleepTimesFlow)
                6 -> SettingsTab(
                    onPermissionGranted = onPermissionGranted,
                    scripts = scripts,
                    statesById = statesById,
                    onStateChange = onStateChange,
                    onAddScript = onAddScript,
                    onUpdateScript = onUpdateScript,
                    onRemoveScript = onRemoveScript,
                    onResetScript = onResetScript
                )
                else -> Box(Modifier.fillMaxSize())
            }
        }
    }

    // Scrim
    AnimatedVisibility(visible = drawerOpen, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable { drawerOpen = false }
        )
    }

    // Right-side drawer
    AnimatedVisibility(
        visible = drawerOpen,
        enter = slideInHorizontally { it },
        exit = slideOutHorizontally { it },
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
    ) {
        Surface(
            Modifier.fillMaxHeight().statusBarsPadding(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                Modifier
                    .padding(vertical = 24.dp)
                    .width(260.dp)
            ) {
                Text(
                    "WAYPOINT",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    ),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    modifier = Modifier.padding(horizontal = 28.dp)
                )
                Text(
                    LocalDate.now().format(
                        DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 28.dp)
                )
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))
                tabLabels.forEachIndexed { i, label ->
                    NavigationDrawerItem(
                        label = { Text(label) },
                        selected = pagerState.currentPage == i,
                        onClick = {
                            scope.launch {
                                drawerOpen = false
                                pagerState.animateScrollToPage(i)
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    }

    } // Box
}

// ── App header ────────────────────────────────────────────────────────────────

@Composable
private fun AppHeader(
    tasksDone: Int,
    tasksTotal: Int,
    onMenuClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.background
    val outline = MaterialTheme.colorScheme.outline

    val progressFraction = if (tasksTotal > 0) tasksDone.toFloat() / tasksTotal else 0f
    val allDone = tasksTotal > 0 && tasksDone == tasksTotal

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

        IconButton(
            onClick = onMenuClick,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 4.dp)
        ) {
            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = primary.copy(alpha = 0.8f))
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
                        tasksTotal == 0 -> "No tasks today"
                        allDone -> "All done"
                        else -> "$tasksDone of $tasksTotal done"
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
    eventPlanner: EventPlannerRegistry,
    calendarSignals: CalendarSignals,
    sleepTimesFlow: StateFlow<Pair<Long?, Long?>>,
    taskManager: TaskManagerScript,
    blockSessionStore: BlockSessionStore,
    blockSessionLogStore: com.waypoint.app.planner.BlockSessionLogStore? = null,
    onHeaderRefresh: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sleepStore = remember { SleepScheduleStore(context) }
    val bufferStore = remember { BufferRulesStore(context) }
    val calPrefsStore = remember { CalendarPrefsStore(context) }
    val namedBlockStore = remember { NamedBlockStore(context) }
    val allNamedBlocks = remember { namedBlockStore.loadAllBlocks() }
    val activeSession by blockSessionStore.sessionFlow.collectAsState()
    val today = remember { LocalDate.now() }
    var dayOffset by remember { mutableIntStateOf(0) }
    val selectedDate = remember(dayOffset) { today.plusDays(dayOffset.toLong()) }
    val dateFmt = remember { DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()) }
    val dayHeaderFmt = remember { DateTimeFormatter.ofPattern("EEE, MMMM d", Locale.getDefault()) }
    val monthFmt = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()) }
    var calRefreshKey by remember { mutableIntStateOf(0) }

    // Calendar grid state
    var calendarExpanded by remember { mutableStateOf(false) }
    var displayMonth by remember { mutableStateOf(YearMonth.now()) }
    var eventDays by remember { mutableStateOf(emptySet<LocalDate>()) }
    var showAddEvent     by remember { mutableStateOf(false) }
    var showAddTask      by remember { mutableStateOf(false) }
    var showAddBlockTask by remember { mutableStateOf(false) }
    val hasCalPermission = remember { calendarSignals.hasPermission() }

    // Timeline tap / detail state
    var selectedCalEvent by remember { mutableStateOf<CalendarEvent?>(null) }
    var selectedPlannerEvent by remember { mutableStateOf<ScheduledEvent?>(null) }
    var editingTask by remember { mutableStateOf<TaskRequest?>(null) }
    var editingCalEvent by remember { mutableStateOf<CalendarEvent?>(null) }
    // Latest calendar events from the timeline — forwarded to AddTaskSheet for conditions
    var planTabCalEvents by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }

    // Keep the calendar grid in sync when day arrows navigate across month boundaries
    LaunchedEffect(selectedDate) {
        val month = YearMonth.from(selectedDate)
        if (month != displayMonth) displayMonth = month
    }

    // Load event dots whenever the visible month or expand state changes
    LaunchedEffect(displayMonth, calRefreshKey, hasCalPermission, calendarExpanded) {
        if (!hasCalPermission || !calendarExpanded) return@LaunchedEffect
        eventDays = loadEventDaysForMonth(context, displayMonth)
    }

    // Refresh timeline whenever sleep times change; re-sync buffer rules with latest sleep anchors
    val sleepTimes by sleepTimesFlow.collectAsState()
    LaunchedEffect(sleepTimes) {
        bufferStore.syncToRegistry(
            registry = eventPlanner,
            sleepStartMs = sleepTimes.first,
            wakeMs = sleepTimes.second
        )
        calRefreshKey++
    }

    Column(Modifier.fillMaxSize()) {

        // ── Day navigation row ────────────────────────────────────────────────
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
            IconButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        sleepStore.syncToRegistry(eventPlanner)
                        bufferStore.syncToRegistry(
                            registry = eventPlanner,
                            sleepStartMs = sleepTimes.first,
                            wakeMs = sleepTimes.second
                        )
                    }
                    calRefreshKey++
                }
            }) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Refresh",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { calendarExpanded = !calendarExpanded }) {
                Icon(
                    if (calendarExpanded) Icons.Filled.KeyboardArrowUp
                    else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (calendarExpanded) "Hide calendar" else "Show calendar",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Collapsible month grid ────────────────────────────────────────────
        if (calendarExpanded) {
            // Month navigation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { displayMonth = displayMonth.minusMonths(1) }) {
                    Icon(
                        Icons.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous month",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = displayMonth.format(monthFmt),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(onClick = { displayMonth = displayMonth.plusMonths(1) }) {
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = "Next month",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Weekday headers
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { label ->
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    )
                }
            }

            // Grid — tapping a day updates dayOffset (arrows still work in parallel)
            MonthGrid(
                month = displayMonth,
                selectedDate = selectedDate,
                eventDays = eventDays,
                today = today,
                onDayClick = { date ->
                    dayOffset = ChronoUnit.DAYS.between(today, date).toInt()
                }
            )

            HorizontalDivider(
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }

        // ── Selected day header ───────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val session = activeSession
            val dayLabel = when {
                selectedDate == today && session != null -> "Today · ${session.blockName}"
                selectedDate == today              -> "Today · ${selectedDate.format(dayHeaderFmt)}"
                selectedDate == today.minusDays(1) -> "Yesterday · ${selectedDate.format(dayHeaderFmt)}"
                selectedDate == today.plusDays(1)  -> "Tomorrow · ${selectedDate.format(dayHeaderFmt)}"
                else                               -> selectedDate.format(dayHeaderFmt)
            }
            Text(
                text = dayLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            TextButton(onClick = {
                if (activeSession != null) showAddBlockTask = true else showAddTask = true
            }) { Text("+ Task") }
            TextButton(onClick = { showAddEvent = true }) { Text("+ Event") }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        val sessionForToday = if (selectedDate == today) activeSession else null
        if (sessionForToday != null) {
            BlockScopeView(
                session = sessionForToday,
                registry = eventPlanner,
                namedBlockStore = namedBlockStore,
                blockLogStore = blockSessionLogStore,
                date = selectedDate,
                modifier = Modifier.weight(1f),
                onEndSession = { blockSessionStore.endSession() },
                onTaskClick = { selectedPlannerEvent = it }
            )
        } else {
            DayTimelineView(
                registry = eventPlanner,
                calendarSignals = calendarSignals,
                calendarPrefsStore = calPrefsStore,
                namedBlockStore = namedBlockStore,
                blockLogStore = blockSessionLogStore,
                date = selectedDate,
                refreshKey = calRefreshKey,
                modifier = Modifier.weight(1f),
                activeBlockId = activeSession?.blockId,
                onBlockStart = { blockId, endMs ->
                    val block = namedBlockStore.loadBlock(blockId) ?: return@DayTimelineView
                    blockSessionStore.startSession(block, endMs, selectedDate)
                },
                onCalendarEventClick = { selectedCalEvent = it },
                onPlannerEventClick = { selectedPlannerEvent = it },
                onCalEventsChanged = { planTabCalEvents = it }
            )
        }
    }

    if (showAddEvent) {
        AddCalendarEventSheet(
            date = selectedDate,
            onDismiss = { showAddEvent = false },
            onSave = { title, startMs, endMs, notes, allDay, reservesTime ->
                scope.launch {
                    val eventId = calendarSignals.createEvent(title, startMs, endMs, notes, allDay)
                    if (eventId > 0) calPrefsStore.setReservesTime(eventId, reservesTime)
                    calRefreshKey++
                    showAddEvent = false
                }
            }
        )
    }

    // ── Calendar event detail ─────────────────────────────────────────────────
    val selCal = selectedCalEvent
    if (selCal != null) {
        TimelineEventDetailSheet(
            item = TimelineDetailItem.CalEvent(selCal),
            onDismiss = { selectedCalEvent = null },
            onEdit = if (selCal.eventId > 0 && calendarSignals.hasWritePermission()) {
                { editingCalEvent = selCal; selectedCalEvent = null }
            } else null,
            onDelete = if (selCal.eventId > 0) {
                {
                    scope.launch {
                        calendarSignals.deleteEvent(selCal.eventId)
                        calRefreshKey++
                        selectedCalEvent = null
                    }
                }
            } else null
        )
    }

    // ── Planner / task detail ─────────────────────────────────────────────────
    val selPlanner = selectedPlannerEvent
    if (selPlanner != null) {
        val taskReq = taskManager.getAllTasks().find { it.id == selPlanner.event.id }
        val isBlockTile = selPlanner.event.id.startsWith("__block__")
        val blockTileId = if (isBlockTile) selPlanner.event.id.removePrefix("__block__") else null
        val isRunning = taskManager.getRunningExecution()?.taskId == selPlanner.event.id
        val isDone = taskManager.completions.isDone(selPlanner.event.id)
        TimelineEventDetailSheet(
            item = TimelineDetailItem.PlannerItem(selPlanner, taskReq),
            onDismiss = { selectedPlannerEvent = null },
            onEdit = if (taskReq != null) {
                { editingTask = taskReq; selectedPlannerEvent = null }
            } else null,
            onDelete = if (taskReq != null) {
                {
                    taskManager.retractTask(selPlanner.event.id)
                    calRefreshKey++
                    selectedPlannerEvent = null
                }
            } else null,
            onStart = if (taskReq != null && !isRunning && !isDone) {
                {
                    taskManager.getRunningExecution()?.let { taskManager.stopExecution(it.taskId) }
                    taskManager.startExecution(selPlanner.event.id)
                    taskManager.syncToRegistry()
                    calRefreshKey++
                    onHeaderRefresh()
                }
            } else null,
            onComplete = if (taskReq != null && !isDone) {
                {
                    if (isRunning) taskManager.stopExecution(selPlanner.event.id)
                    taskManager.markDone(selPlanner.event.id)
                    taskManager.syncToRegistry()
                    calRefreshKey++
                    onHeaderRefresh()
                }
            } else null,
            onBlockStart = if (blockTileId != null && activeSession == null) {
                {
                    val block = namedBlockStore.loadBlock(blockTileId)
                    if (block != null) {
                        blockSessionStore.startSession(block, selPlanner.endMillis, selectedDate)
                    }
                }
            } else null,
        )
    }

    // ── Add task from Plan tab header ─────────────────────────────────────────
    if (showAddTask) {
        AddTaskSheet(
            availableTasks = remember { taskManager.getAllTasks() },
            calendarEvents = planTabCalEvents,
            availableBlocks = allNamedBlocks,
            onDismiss = { showAddTask = false },
            onSave = { req ->
                taskManager.submitTask(req)
                calRefreshKey++
                showAddTask = false
            }
        )
    }

    // ── Add block task from Plan tab header (session mode) ────────────────────
    if (showAddBlockTask) {
        val session = activeSession
        if (session != null) {
            AddTaskSheet(
                forBlock = session.blockId,
                onDismiss = { showAddBlockTask = false },
                onSaveBlockTask = { blockTask ->
                    namedBlockStore.saveTask(blockTask)
                    calRefreshKey++
                    showAddBlockTask = false
                }
            )
        } else {
            showAddBlockTask = false
        }
    }

    // ── Edit task via AddTaskSheet ────────────────────────────────────────────
    val taskBeingEdited = editingTask
    if (taskBeingEdited != null) {
        AddTaskSheet(
            initial = taskBeingEdited,
            availableTasks = remember { taskManager.getAllTasks() },
            calendarEvents = planTabCalEvents,
            availableBlocks = allNamedBlocks,
            onDismiss = { editingTask = null },
            onSave = { req ->
                taskManager.submitTask(req)
                calRefreshKey++
                editingTask = null
            }
        )
    }

    // ── Edit calendar event via AddCalendarEventSheet ─────────────────────────
    val calBeingEdited = editingCalEvent
    if (calBeingEdited != null) {
        val eventDate = remember(calBeingEdited.startMillis) {
            Instant.ofEpochMilli(calBeingEdited.startMillis)
                .atZone(ZoneId.systemDefault()).toLocalDate()
        }
        AddCalendarEventSheet(
            date = eventDate,
            initialCalEvent = calBeingEdited,
            initialReservesTime = calPrefsStore.reservesTime(calBeingEdited.eventId),
            onDismiss = { editingCalEvent = null },
            onSave = { title, startMs, endMs, notes, allDay, reservesTime ->
                scope.launch {
                    calendarSignals.updateEvent(calBeingEdited.eventId, title, startMs, endMs, notes, allDay)
                    calPrefsStore.setReservesTime(calBeingEdited.eventId, reservesTime)
                    calRefreshKey++
                    editingCalEvent = null
                }
            }
        )
    }
}

// ── Month grid ─────────────────────────────────────────────────────────────────

@Composable
private fun MonthGrid(
    month: YearMonth,
    selectedDate: LocalDate,
    eventDays: Set<LocalDate>,
    today: LocalDate,
    onDayClick: (LocalDate) -> Unit
) {
    val firstDay = month.atDay(1)
    val startOffset = firstDay.dayOfWeek.value - 1  // 0 = Mon, 6 = Sun
    val daysInMonth = month.lengthOfMonth()
    val totalWeeks = (startOffset + daysInMonth + 6) / 7

    Column(Modifier.fillMaxWidth()) {
        for (week in 0 until totalWeeks) {
            Row(Modifier.fillMaxWidth()) {
                for (dow in 0 until 7) {
                    val dayNum = week * 7 + dow - startOffset + 1
                    Box(Modifier.weight(1f)) {
                        if (dayNum in 1..daysInMonth) {
                            val date = month.atDay(dayNum)
                            DayCell(
                                day = dayNum,
                                isToday = date == today,
                                isSelected = date == selectedDate,
                                hasEvents = date in eventDays,
                                onClick = { onDayClick(date) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    isToday: Boolean,
    isSelected: Boolean,
    hasEvents: Boolean,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer

    val circleBg = when {
        isSelected -> primary
        isToday    -> primaryContainer
        else       -> Color.Transparent
    }
    val textColor = when {
        isSelected -> onPrimary
        isToday    -> onPrimaryContainer
        else       -> MaterialTheme.colorScheme.onSurface
    }
    val dotColor = if (isSelected) onPrimary.copy(alpha = 0.65f) else primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(circleBg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = day.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = textColor
            )
        }
        if (hasEvents) {
            Box(
                Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        } else {
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ── Range query — which days in a month have at least one event ────────────────

private suspend fun loadEventDaysForMonth(context: Context, month: YearMonth): Set<LocalDate> =
    withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val startMs = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = month.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMs.toString())
            .appendPath(endMs.toString())
            .build()

        val days = mutableSetOf<LocalDate>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.BEGIN),
                null, null, null
            )?.use { cursor ->
                val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                while (cursor.moveToNext()) {
                    days.add(
                        Instant.ofEpochMilli(cursor.getLong(beginIdx))
                            .atZone(zone).toLocalDate()
                    )
                }
            }
        }
        days
    }
