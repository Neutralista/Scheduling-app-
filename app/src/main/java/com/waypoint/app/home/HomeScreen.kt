package com.waypoint.app.home

import com.waypoint.app.planner.TagNames
import com.waypoint.app.planner.conditionTagViews
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import com.waypoint.app.planner.ActiveBlockSession
import com.waypoint.app.planner.BlockSessionStore
import com.waypoint.app.ui.theme.ThemeStore
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
import androidx.compose.runtime.saveable.rememberSaveable
import com.waypoint.app.planner.PlanZoomLevel
import com.waypoint.app.planner.PlanOverviewLoader
import com.waypoint.app.planner.DaySummary
import com.waypoint.app.planner.weekDays
import com.waypoint.app.planner.weekStart
import com.waypoint.app.planner.monthWeekStarts
import com.waypoint.app.planner.planLiveDay
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import com.waypoint.app.planner.BlockTask
import com.waypoint.app.planner.CalendarPrefsStore
import com.waypoint.app.planner.NamedBlock
import com.waypoint.app.planner.ScheduledEvent
import com.waypoint.app.planner.SleepLogStore
import com.waypoint.app.planner.SleepModeState
import com.waypoint.app.planner.SleepCheckReceiver
import com.waypoint.app.planner.SleepSchedule
import com.waypoint.app.planner.TaskRequest
import com.waypoint.app.signal.CalendarEvent
import com.waypoint.app.signal.ShiftTime
import com.waypoint.app.ui.components.TimePickerChip

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
    themeStore: ThemeStore? = null,
    scripts: List<AppScript>,
    statesById: Map<String, ScriptState>,
    onStateChange: (scriptId: String, newState: ScriptState) -> Unit,
    onAddScript: (String) -> String?,
    onUpdateScript: (id: String, newSource: String) -> String?,
    onRemoveScript: (String) -> Unit,
    onResetScript: (String) -> Unit,
    onPermissionGranted: () -> Unit,
    initialTab: Int = 0,
    externalTabRequest: Int? = null,
    onTabNavigated: () -> Unit = {}
) {
    val pagerState = rememberPagerState(initialPage = initialTab) { 6 }
    val scope = rememberCoroutineScope()

    LaunchedEffect(externalTabRequest) {
        val tab = externalTabRequest ?: return@LaunchedEffect
        pagerState.animateScrollToPage(tab)
        onTabNavigated()
    }
    var drawerOpen by remember { mutableStateOf(false) }
    var headerRefreshKey by remember { mutableIntStateOf(0) }

    val context = LocalContext.current
    val namedBlockStore = remember { NamedBlockStore(context) }
    val allNamedBlocks = remember { namedBlockStore.loadAllBlocks() }

    // Planned exactly as the Plan tab's timeline plans today, so the header's count agrees with it.
    val today = remember { LocalDate.now() }
    val headerSession by blockSessionStore.sessionFlow.collectAsState()
    val headerCalPrefs = remember { CalendarPrefsStore(context) }
    var headerPlan by remember {
        mutableStateOf(eventPlanner.planToday(
            namedBlockInstances = namedBlockStore.resolveFixedInstancesForDate(today),
            floatingBlocks = namedBlockStore.resolveFloatingInstancesForDate(today)
        ))
    }
    LaunchedEffect(headerRefreshKey, headerSession) {
        while (true) {
            headerPlan = planLiveDay(eventPlanner, namedBlockStore, blockSessionLogStore, headerSession, calendarSignals, headerCalPrefs, today)
            delay(60_000L)
        }
    }
    // Block sub-tasks (before/during/after a named block) carry the block's synthetic event id
    // as their sourceWidgetId ("__block__<blockId>") rather than the flat-task widget id — count
    // them too, or the header's progress bar silently ignores every task scheduled inside a block.
    val plannerScheduled = remember(headerPlan) {
        headerPlan.scheduled.filter {
            it.event.sourceWidgetId == TaskManagerScript.WIDGET_ID ||
                it.event.sourceWidgetId?.startsWith("__block__") == true
        }
    }
    val plannerDoneIds = remember(headerRefreshKey) { taskManager.completions.getDoneIds() }

    val tasksTotal = plannerScheduled.size
    val tasksDone = plannerScheduled.count { it.event.id in plannerDoneIds }

    val tabLabels = listOf("Plan", "History", "Tasks", "Blocks", "Alarms", "Settings")

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        AppHeader(
            tasksDone = tasksDone,
            tasksTotal = tasksTotal,
            onMenuClick = { drawerOpen = true }
        )
        val accentColor = MaterialTheme.colorScheme.error
        ScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
            edgePadding = 0.dp,
            indicator = { tabPositions ->
                if (pagerState.currentPage < tabPositions.size) {
                    Box(
                        Modifier
                            .tabIndicatorOffset(tabPositions[pagerState.currentPage])
                            .height(2.dp)
                            .background(accentColor)
                    )
                }
            }
        ) {
            tabLabels
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
                1 -> HistoryTab(cycleTracker = cycleTracker, taskManager = taskManager, blockSessionLogStore = blockSessionLogStore, namedBlockStore = namedBlockStore)
                2 -> TasksTab(registry = eventPlanner, taskManager = taskManager, calendarSignals = calendarSignals, blockSessionStore = blockSessionStore, availableBlocks = allNamedBlocks, onRefresh = { headerRefreshKey++ })
                3 -> BlocksTab(taskManager = taskManager, eventPlanner = eventPlanner, externalRefreshKey = headerRefreshKey, calendarSignals = calendarSignals)
                4 -> AlarmsTab(alarms = alarms, sleepTimesFlow = sleepTimesFlow)
                // Modules (user scripts) live in Settings, under Scripts.
                5 -> SettingsTab(
                    onPermissionGranted = onPermissionGranted,
                    themeStore = themeStore,
                    scriptsCount = scripts.size,
                    scriptsContent = {
                        ScriptsTab(
                            scripts = scripts,
                            statesById = statesById,
                            onStateChange = onStateChange,
                            onAddScript = onAddScript,
                            onUpdateScript = onUpdateScript,
                            onRemoveScript = onRemoveScript,
                            onResetScript = onResetScript
                        )
                    }
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
    val accent = MaterialTheme.colorScheme.error
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
                                .background(if (allDone) accent else primary)
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

@OptIn(ExperimentalMaterial3Api::class)
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
    val calPrefsStore = remember { CalendarPrefsStore(context) }
    val namedBlockStore = remember { NamedBlockStore(context) }
    val allNamedBlocks = remember { namedBlockStore.loadAllBlocks() }
    val activeSession by blockSessionStore.sessionFlow.collectAsState()
    // Whether the user has stepped out of the active session's block-scope view back to the
    // normal timeline. Purely a local UI choice — it never touches the session itself, so the
    // block keeps running (timer, notification) until the user explicitly ends it. Keyed on the
    // session's blockId so a genuinely new session (not just a day-navigation blip) re-shows
    // scope by default.
    var blockScopeHidden by remember(activeSession?.blockId) { mutableStateOf(false) }
    val today = remember { LocalDate.now() }
    var dayOffset by remember { mutableIntStateOf(0) }
    val selectedDate = remember(dayOffset) { today.plusDays(dayOffset.toLong()) }
    val dateFmt = remember { DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()) }
    var calRefreshKey by remember { mutableIntStateOf(0) }

    // Zoom level: the day's timeline, or tiles for its week, month or year. selectedDate is the
    // anchor at every level; tapping a tile moves it and zooms in.
    var zoomLevel by rememberSaveable { mutableStateOf(PlanZoomLevel.DAY) }
    fun goTo(date: LocalDate, level: PlanZoomLevel) {
        dayOffset = ChronoUnit.DAYS.between(today, date).toInt()
        zoomLevel = level
    }
    val overviewLoader = remember { PlanOverviewLoader(eventPlanner, namedBlockStore, calendarSignals, calPrefsStore, isDone = { taskManager.isDone(it) }) }
    val overviewDates = remember(zoomLevel, selectedDate) {
        when (zoomLevel) {
            PlanZoomLevel.DAY -> emptyList()
            PlanZoomLevel.WEEK -> weekDays(selectedDate)
            PlanZoomLevel.MONTH -> monthWeekStarts(YearMonth.from(selectedDate)).flatMap { weekDays(it) }
            PlanZoomLevel.YEAR -> {
                val jan1 = LocalDate.of(selectedDate.year, 1, 1)
                (0 until jan1.lengthOfYear()).map { jan1.plusDays(it.toLong()) }
            }
        }
    }
    var overview by remember { mutableStateOf<Map<LocalDate, DaySummary>?>(null) }
    LaunchedEffect(overviewDates, calRefreshKey) {
        if (overviewDates.isEmpty()) return@LaunchedEffect
        overview = null
        // A year of days isn't planned one by one: its tiles count fixed blocks and events.
        overview = runCatching { overviewLoader.load(overviewDates, withPlan = zoomLevel != PlanZoomLevel.YEAR) }
            .getOrElse { emptyMap() }
    }

    // Timeline tap / detail state
    var selectedCalEvent by remember { mutableStateOf<CalendarEvent?>(null) }
    var selectedPlannerEvent by remember { mutableStateOf<ScheduledEvent?>(null) }
    var editingTask by remember { mutableStateOf<TaskRequest?>(null) }
    var editingCalEvent by remember { mutableStateOf<CalendarEvent?>(null) }
    var editingBlock by remember { mutableStateOf<NamedBlock?>(null) }
    var editingBlockTask by remember { mutableStateOf<Pair<String, BlockTask?>?>(null) }
    var showEditSleep by remember { mutableStateOf(false) }
    var planningBlockId by remember { mutableStateOf<String?>(null) }
    // Captured from the tapped tile's actual scheduled window at the moment "Plan" is pressed —
    // resolveForDate only returns fixed blocks, so re-deriving the window from it (as this used
    // to) silently failed for floating ("auto-place") blocks. selPlanner's window is already
    // correct for either kind since it comes straight from that day's planner output.
    var planningWindow by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var freeSlot by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var freeSlotFromBlock by remember { mutableStateOf(false) }
    // Latest calendar events from the timeline — forwarded to AddTaskSheet for conditions
    var planTabCalEvents by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }

    // Refresh timeline whenever sleep times change
    val sleepTimes by sleepTimesFlow.collectAsState()
    LaunchedEffect(sleepTimes) {
        calRefreshKey++
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {

        // ── Period navigation row ─────────────────────────────────────────────
        if (zoomLevel == PlanZoomLevel.DAY) {
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
                    if (dayOffset == 0) {
                        Text(
                            text = "Full day timeline",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
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
        } else {
            val isCurrentPeriod = when (zoomLevel) {
                PlanZoomLevel.WEEK -> weekStart(selectedDate) == weekStart(today)
                PlanZoomLevel.MONTH -> YearMonth.from(selectedDate) == YearMonth.from(today)
                else -> selectedDate.year == today.year
            }
            fun shift(by: Long) = when (zoomLevel) {
                PlanZoomLevel.WEEK -> goTo(selectedDate.plusWeeks(by), zoomLevel)
                PlanZoomLevel.MONTH -> goTo(selectedDate.plusMonths(by), zoomLevel)
                else -> goTo(selectedDate.plusYears(by), zoomLevel)
            }
            val unit = zoomLevel.name.lowercase()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { shift(-1) }) {
                    Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Previous $unit",
                        modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(if (!isCurrentPeriod) Modifier.clickable { goTo(today, zoomLevel) } else Modifier)
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = zoomPeriodLabel(zoomLevel, selectedDate),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (isCurrentPeriod) "This $unit" else "tap to return to this $unit",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCurrentPeriod) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { shift(1) }) {
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Next $unit",
                        modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        ZoomLevelSwitcher(
            level = zoomLevel,
            onSelect = { zoomLevel = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        )

        val sessionForToday = if (selectedDate == today && !blockScopeHidden) activeSession else null
        val planningSession = planningBlockId?.let { blockId ->
            val block = namedBlockStore.loadBlock(blockId)
            val window = planningWindow
            if (block != null && window != null) {
                ActiveBlockSession(
                    blockId = blockId,
                    blockName = block.name,
                    colorArgb = block.colorArgb,
                    startedAtMs = window.first,
                    scheduledEndMs = window.second,
                    date = selectedDate.toString()
                )
            } else null
        }
        val displaySession = sessionForToday ?: planningSession
        val overviewModifier = Modifier.weight(1f).pinchZoomLevels(
            onZoomIn = { zoomLevel.inner?.let { zoomLevel = it } },
            onZoomOut = { zoomLevel.outer?.let { zoomLevel = it } }
        )
        if (zoomLevel == PlanZoomLevel.WEEK) {
            WeekOverview(
                anchor = selectedDate,
                summaries = overview,
                onDayClick = { goTo(it, PlanZoomLevel.DAY) },
                modifier = overviewModifier
            )
        } else if (zoomLevel == PlanZoomLevel.MONTH) {
            MonthOverview(
                month = YearMonth.from(selectedDate),
                summaries = overview,
                onWeekClick = { monday ->
                    goTo(if (today in weekDays(monday)) today else monday, PlanZoomLevel.WEEK)
                },
                modifier = overviewModifier
            )
        } else if (zoomLevel == PlanZoomLevel.YEAR) {
            YearOverview(
                year = selectedDate.year,
                summaries = overview,
                onMonthClick = { month ->
                    goTo(if (YearMonth.from(today) == month) today else month.atDay(1), PlanZoomLevel.MONTH)
                },
                modifier = overviewModifier
            )
        } else if (displaySession != null) {
            BlockScopeView(
                session = displaySession,
                isPlanningMode = planningSession != null && sessionForToday == null,
                registry = eventPlanner,
                namedBlockStore = namedBlockStore,
                blockLogStore = blockSessionLogStore,
                calendarSignals = calendarSignals,
                calendarPrefsStore = calPrefsStore,
                date = selectedDate,
                refreshKey = calRefreshKey,
                modifier = Modifier.weight(1f),
                onEndSession = {
                    // With the tasks ticked off so far, not logged as "0 of 0".
                    if (sessionForToday != null) blockSessionStore.endIfBlock(sessionForToday.blockId)
                    else { planningBlockId = null; planningWindow = null }
                },
                onExitScope = if (sessionForToday != null) { { blockScopeHidden = true } } else null,
                onTaskClick = { selectedPlannerEvent = it },
                onEditTask = { task -> editingBlockTask = task.blockId to task },
                onColorChanged = { argb -> if (sessionForToday != null) blockSessionStore.updateColor(argb) },
                onFreeSlotClick = { startMs, endMs -> freeSlot = startMs to endMs; freeSlotFromBlock = true }
            )
        } else {
            DayTimelineView(
                registry = eventPlanner,
                calendarSignals = calendarSignals,
                calendarPrefsStore = calPrefsStore,
                namedBlockStore = namedBlockStore,
                blockLogStore = blockSessionLogStore,
                taskManager = taskManager,
                date = selectedDate,
                refreshKey = calRefreshKey,
                modifier = Modifier.weight(1f),
                sleepSchedule = sleepStore.load(),
                activeBlockId = activeSession?.blockId,
                activeSession = activeSession,
                onBlockStart = { blockId ->
                    val block = namedBlockStore.loadBlock(blockId) ?: return@DayTimelineView
                    val now = System.currentTimeMillis()
                    blockSessionStore.startSession(block, now + namedBlockStore.plannedDurationMs(block, selectedDate), selectedDate, now)
                },
                onBlockStartAt = { blockId, startedAtMs ->
                    val block = namedBlockStore.loadBlock(blockId) ?: return@DayTimelineView
                    blockSessionStore.startSession(block, startedAtMs + namedBlockStore.plannedDurationMs(block, selectedDate), selectedDate, startedAtMs)
                },
                onBlockSkip = { blockId ->
                    // Skipping the block that's running today ends its session too.
                    if (selectedDate == LocalDate.now()) blockSessionStore.endIfBlock(blockId)
                    namedBlockStore.skipForDate(blockId, selectedDate)
                    calRefreshKey++
                },
                onCalendarEventClick = { selectedCalEvent = it },
                onPlannerEventClick = { selectedPlannerEvent = it },
                onCalEventsChanged = { planTabCalEvents = it },
                onFreeSlotClick = { startMs, endMs -> freeSlot = startMs to endMs; freeSlotFromBlock = false },
                onZoomOut = { zoomLevel = PlanZoomLevel.WEEK }
            )
        }
    }

    // ── Adding from a free slot: Task, Block or Event (just a task inside a block) ──
    val slot = freeSlot
    if (slot != null) {
        AddAnythingSheet(
            date = selectedDate,
            taskManager = taskManager,
            namedBlockStore = namedBlockStore,
            eventPlanner = eventPlanner,
            calendarSignals = calendarSignals,
            calendarPrefs = calPrefsStore,
            calendarEvents = planTabCalEvents,
            slot = slot,
            forBlockId = if (freeSlotFromBlock) activeSession?.blockId ?: planningBlockId else null,
            onDismiss = { freeSlot = null },
            onAdded = { calRefreshKey++ }
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
                        calendarSignals.deleteEvent(selCal.eventId, selCal.startMillis)
                        calPrefsStore.clear(selCal.eventId)
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
        val isSleepEvent = selPlanner.event.id.startsWith("sleep_")
        val blockTileId = if (isBlockTile) selPlanner.event.id.removePrefix("__block__") else null
        val subTaskBlockId = if (!isBlockTile && !isSleepEvent && taskReq == null)
            selPlanner.event.sourceWidgetId?.removePrefix("__block__")?.takeIf {
                selPlanner.event.sourceWidgetId?.startsWith("__block__") == true
            } else null
        val isBlockSubTask = subTaskBlockId != null
        val isRunning = taskManager.getRunningExecution()?.taskId == selPlanner.event.id
        val isDone = taskManager.completions.isDone(selPlanner.event.id)
        val plannerTags = remember(selPlanner, taskReq) {
            val conditions = when {
                taskReq != null -> taskReq.conditions
                isBlockTile && blockTileId != null ->
                    namedBlockStore.loadBlock(blockTileId)?.takeIf { it.isFloating }?.floatingConditions.orEmpty()
                isBlockSubTask -> namedBlockStore.loadTask(selPlanner.event.id)?.conditions.orEmpty()
                else -> emptyList()
            }
            val tasks = taskManager.getAllTasks()
            conditionTagViews(conditions, TagNames(
                task = { id -> tasks.find { it.id == id }?.title },
                block = { id -> allNamedBlocks.find { it.id == id }?.name }
            ))
        }
        TimelineEventDetailSheet(
            item = TimelineDetailItem.PlannerItem(selPlanner, taskReq),
            tags = plannerTags,
            onDismiss = { selectedPlannerEvent = null },
            onEdit = when {
                taskReq != null -> { { editingTask = taskReq; selectedPlannerEvent = null } }
                isSleepEvent -> { { showEditSleep = true; selectedPlannerEvent = null } }
                isBlockTile && blockTileId != null -> { {
                    editingBlock = namedBlockStore.loadBlock(blockTileId)
                    selectedPlannerEvent = null
                } }
                isBlockSubTask && subTaskBlockId != null -> { {
                    editingBlockTask = subTaskBlockId to namedBlockStore.loadTask(selPlanner.event.id)
                    selectedPlannerEvent = null
                } }
                else -> null
            },
            onDelete = when {
                taskReq != null -> { {
                    taskManager.retractTask(selPlanner.event.id)
                    calRefreshKey++
                    selectedPlannerEvent = null
                } }
                isBlockTile && blockTileId != null -> { {
                    namedBlockStore.deleteBlock(blockTileId)
                    taskManager.dropBlockConditions(blockTileId)
                    calRefreshKey++
                    selectedPlannerEvent = null
                } }
                isBlockSubTask -> { {
                    namedBlockStore.deleteTask(selPlanner.event.id)
                    calRefreshKey++
                    selectedPlannerEvent = null
                } }
                else -> null
            },
            // Dragged to a time on today's timeline: let it be planned freely again.
            onUnpin = if (taskReq != null && taskManager.isPinned(selPlanner.event.id, selectedDate)) {
                {
                    taskManager.unpin(selPlanner.event.id, selectedDate)
                    calRefreshKey++
                    selectedPlannerEvent = null
                }
            } else null,
            onSkip = if ((taskReq != null || isBlockSubTask) && !taskManager.isSkipped(selPlanner.event.id)) {
                {
                    if (isRunning) taskManager.stopExecution(selPlanner.event.id)
                    taskManager.skipTask(selPlanner.event.id)
                    calRefreshKey++
                    onHeaderRefresh()
                    selectedPlannerEvent = null
                }
            } else null,
            onStart = if ((taskReq != null || isBlockSubTask) && !isRunning && !isDone) {
                {
                    taskManager.getRunningExecution()?.let { taskManager.stopExecution(it.taskId) }
                    taskManager.startExecution(selPlanner.event.id)
                    taskManager.syncToRegistry()
                    calRefreshKey++
                    onHeaderRefresh()
                }
            } else null,
            onComplete = if ((taskReq != null || isBlockSubTask) && !isDone) {
                {
                    if (isRunning) taskManager.stopExecution(selPlanner.event.id)
                    taskManager.markDone(selPlanner.event.id)
                    taskManager.syncToRegistry()
                    calRefreshKey++
                    onHeaderRefresh()
                }
            } else null,
            // Today only: a session started from another day's plan is dated to that day and
            // was thrown away at once as stale.
            onBlockStart = if (blockTileId != null && activeSession == null && selectedDate == LocalDate.now()) {
                {
                    val block = namedBlockStore.loadBlock(blockTileId)
                    if (block != null) {
                        val now = System.currentTimeMillis()
                        blockSessionStore.startSession(block, now + namedBlockStore.plannedDurationMs(block, selectedDate), selectedDate, now)
                    }
                }
            } else null,
            // The tile for the currently-running (but hidden) session's own block — reachable
            // here only because block scope was hidden, since it would otherwise be showing
            // full-screen instead of this timeline. Offer to step back in rather than "Start".
            onResume = if (blockTileId != null && blockTileId == activeSession?.blockId) {
                { blockScopeHidden = false }
            } else null,
            onPlan = if (blockTileId != null && activeSession == null && planningBlockId == null) {
                {
                    planningBlockId = blockTileId
                    planningWindow = selPlanner.startMillis to selPlanner.endMillis
                    selectedPlannerEvent = null
                }
            } else null,
            onSleepMode = if (isSleepEvent) {
                {
                    val logStore = SleepLogStore(context)
                    if (logStore.getSleepModeState() != SleepModeState.IDLE) {
                        logStore.cancelSleepMode()
                    } else {
                        logStore.enterSleepMode()
                        SleepCheckReceiver.scheduleNextCheck(context)
                    }
                }
            } else null,
            isSleepModeActive = isSleepEvent && remember(selPlanner) {
                SleepLogStore(context).getSleepModeState() != SleepModeState.IDLE
            },
        )
    }

    // ── Edit task via AddTaskSheet ────────────────────────────────────────────
    val taskBeingEdited = editingTask
    if (taskBeingEdited != null) {
        AddTaskSheet(
            initial = taskBeingEdited,
            availableTasks = remember { taskManager.getAllTasks() },
            calendarEvents = planTabCalEvents,
            availableBlocks = allNamedBlocks,
            eventPlanner = eventPlanner,
            namedBlockStore = namedBlockStore,
            onDismiss = { editingTask = null },
            onSave = { req ->
                taskManager.submitTask(req)
                calRefreshKey++
                editingTask = null
            }
        )
    }

    // ── Edit block task via AddTaskSheet ─────────────────────────────────────
    val blockTaskBeingEdited = editingBlockTask
    if (blockTaskBeingEdited != null) {
        AddTaskSheet(
            forBlock = blockTaskBeingEdited.first,
            blockPhases = namedBlockStore.loadBlock(blockTaskBeingEdited.first)?.phases.orEmpty(),
            initialBlockTask = blockTaskBeingEdited.second,
            availableTasks = remember { taskManager.getAllTasks() },
            calendarEvents = planTabCalEvents,
            availableBlocks = allNamedBlocks,
            onDismiss = { editingBlockTask = null },
            onSaveBlockTask = { task ->
                namedBlockStore.saveTask(task)
                calRefreshKey++
                editingBlockTask = null
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
            onSave = { title, startMs, endMs, notes, allDay, reservesTime, _ ->
                scope.launch {
                    calendarSignals.updateEvent(
                        calBeingEdited.eventId, title, startMs, endMs, notes, allDay,
                        instanceStartMillis = calBeingEdited.startMillis
                    )
                    calPrefsStore.setReservesTime(calBeingEdited.eventId, reservesTime)
                    calRefreshKey++
                    editingCalEvent = null
                }
            }
        )
    }

    // ── Edit named block via NamedBlockSheet ──────────────────────────────────
    val blockBeingEdited = editingBlock
    if (blockBeingEdited != null) {
        NamedBlockSheet(
            initial = blockBeingEdited,
            store = namedBlockStore,
            availableTasks = remember { taskManager.getAllTasks() },
            calendarEvents = planTabCalEvents,
            onDismiss = { editingBlock = null },
            onSaved = {
                calRefreshKey++
                editingBlock = null
            }
        )
    }

    // ── Edit sleep schedule from timeline ─────────────────────────────────────
    if (showEditSleep) {
        SleepEditSheet(
            sleepStore = sleepStore,
            registry = eventPlanner,
            onDismiss = { showEditSleep = false; calRefreshKey++ }
        )
    }
}

// ── Sleep edit bottom sheet ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepEditSheet(
    sleepStore: SleepScheduleStore,
    registry: EventPlannerRegistry,
    onDismiss: () -> Unit
) {
    var schedule by remember { mutableStateOf(sleepStore.load()) }

    fun commit(updated: SleepSchedule) {
        schedule = updated
        sleepStore.syncToRegistry(registry)
        schedule = sleepStore.load()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Sleep",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (schedule.enabled) "Enabled" else "Disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = schedule.enabled,
                        onCheckedChange = { enabled ->
                            sleepStore.setEnabled(enabled)
                            commit(sleepStore.load())
                        }
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Wake",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    TimePickerChip(
                        value = schedule.preferredWakeTime.displayString,
                        onValueChange = { text ->
                            ShiftTime.parse(text)?.let {
                                sleepStore.setPreferredWakeTime(it)
                                commit(sleepStore.load())
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Bed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    TimePickerChip(
                        value = schedule.preferredBedTime.displayString,
                        onValueChange = { text ->
                            ShiftTime.parse(text)?.let {
                                sleepStore.setPreferredBedTime(it)
                                commit(sleepStore.load())
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Reminder alarms, wake-up count, and block sync are in the Blocks tab → Sleep.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
