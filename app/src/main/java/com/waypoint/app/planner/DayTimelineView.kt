package com.waypoint.app.planner

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waypoint.app.script.TaskManagerScript
import com.waypoint.app.signal.CalendarEvent
import com.waypoint.app.signal.CalendarSignals
import com.waypoint.app.planner.CalendarPrefsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

// The visible window runs from VIEW_START_HOUR on the selected date to
// VIEW_START_HOUR on the following date (e.g. 4 AM → 4 AM).
// All internal "minute" values are relative to that 4 AM anchor.
private const val VIEW_START_HOUR = 4
private const val START_HOUR = 0          // relative minute 0 = VIEW_START_HOUR
private const val END_HOUR   = 24         // relative minute 1440 = next-day VIEW_START_HOUR
private const val TOTAL_HOURS = END_HOUR - START_HOUR
private val HOUR_HEIGHT = 67.dp
private val LABEL_WIDTH = 44.dp

/** Converts a relative-minute value to a Y offset within the timeline. */
private fun minToY(minutes: Int, hourHeight: Dp): Dp =
    hourHeight * (minutes.coerceAtLeast(0) / 60f)

/** Returns minutes elapsed since the 4 AM view-start anchor. Negative = before the window. */
private fun minutesFromViewStart(viewStartMs: Long): Int =
    ((System.currentTimeMillis() - viewStartMs) / 60_000L).toInt()

/** Converts an absolute timestamp to minutes relative to the 4 AM view-start anchor. */
private fun msToMin(ms: Long, viewStartMs: Long): Int =
    ((ms - viewStartMs) / 60_000L).toInt()

private fun fmtMs(ms: Long): String =
    Calendar.getInstance().apply { timeInMillis = ms }
        .let { "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }

/** Rounds a raw drag delta (in minutes) to the nearest 5-minute increment. */
private fun snapMinutesDelta(rawMinutes: Float): Int =
    (kotlin.math.round(rawMinutes / 5f) * 5f).toInt()

/** Positions a child at an absolute y offset within its parent Box via layout. */
private fun Modifier.yOffset(y: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.placeRelative(0, y.roundToPx())
    }
}

/** Overrides durationMinutes on tasks with useMeasuredDuration using historical averages. */
private fun List<BlockTask>.withMeasuredDurations(logStore: BlockSessionLogStore?): List<BlockTask> {
    if (logStore == null) return this
    return map { task ->
        if (task.useMeasuredDuration) {
            val avg = logStore.averageMeasuredMinutes(task.id)
            if (avg != null) task.copy(durationMinutes = avg) else task
        } else task
    }
}

@Composable
fun DayTimelineView(
    registry: EventPlannerRegistry,
    calendarSignals: CalendarSignals? = null,
    calendarPrefsStore: CalendarPrefsStore? = null,
    namedBlockStore: NamedBlockStore? = null,
    blockLogStore: BlockSessionLogStore? = null,
    taskManager: TaskManagerScript? = null,
    date: LocalDate = LocalDate.now(),
    refreshKey: Int = 0,
    modifier: Modifier = Modifier,
    sleepSchedule: SleepSchedule? = null,
    activeBlockId: String? = null,
    onBlockStart: ((blockId: String, scheduledEndMs: Long) -> Unit)? = null,
    onCalendarEventClick: ((CalendarEvent) -> Unit)? = null,
    onPlannerEventClick: ((ScheduledEvent) -> Unit)? = null,
    onCalEventsChanged: ((List<CalendarEvent>) -> Unit)? = null,
    onFreeSlotClick: ((startMs: Long, endMs: Long) -> Unit)? = null
) {
    val isToday = date == LocalDate.now()
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()

    // Bumped after an in-place drag/resize edit (calendar event or named block) commits, so
    // the memoized instance/plan blocks below recompute without waiting on a refresh from
    // the parent screen.
    var localRefreshKey by remember { mutableIntStateOf(0) }

    val blockInstances = remember(date, refreshKey, localRefreshKey) {
        namedBlockStore?.resolveForDate(date)?.map { (block, sched) ->
            val startMs = date.atTime(sched.startHour, sched.startMinute)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val activeTasks = namedBlockStore.resolveActiveTasks(block.id, date).withMeasuredDurations(blockLogStore)
            val endMs = if (sched.endHour >= 0) {
                val e = date.atTime(sched.endHour, sched.endMinute)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (e > startMs) e else e + 24 * 3600_000L
            } else {
                startMs + effectiveDurationMinutes(block, activeTasks) * 60_000L
            }
            NamedBlockInstance(block, startMs, endMs, activeTasks)
        } ?: emptyList()
    }
    val floatingBlockInstances = remember(date, refreshKey) {
        namedBlockStore?.loadAllBlocks()?.filter { it.isFloating }?.map { block ->
            NamedBlockInstance(block, 0L, 0L,
                namedBlockStore.resolveActiveTasks(block.id, date).withMeasuredDurations(blockLogStore))
        } ?: emptyList()
    }

    val viewStartMs = remember(date) {
        date.atTime(VIEW_START_HOUR, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val viewEndMs = remember(viewStartMs) { viewStartMs + TOTAL_HOURS * 3600_000L }

    var isNowVisible by remember(viewStartMs, viewEndMs) {
        mutableStateOf(System.currentTimeMillis() in viewStartMs until viewEndMs)
    }
    val nextDayBlockInstances = remember(date, refreshKey, localRefreshKey) {
        val nextDate = date.plusDays(1)
        namedBlockStore?.resolveForDate(nextDate)?.map { (block, sched) ->
            val startMs = nextDate.atTime(sched.startHour, sched.startMinute)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val activeTasks = namedBlockStore.resolveActiveTasks(block.id, nextDate).withMeasuredDurations(blockLogStore)
            val endMs = if (sched.endHour >= 0) {
                val e = nextDate.atTime(sched.endHour, sched.endMinute)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (e > startMs) e else e + 24 * 3600_000L
            } else {
                startMs + effectiveDurationMinutes(block, activeTasks) * 60_000L
            }
            NamedBlockInstance(block, startMs, endMs, activeTasks)
        } ?: emptyList()
    }
    val nextDayFloatingInstances = remember(date, refreshKey) {
        val nextDate = date.plusDays(1)
        namedBlockStore?.loadAllBlocks()?.filter { it.isFloating }?.map { block ->
            NamedBlockInstance(block, 0L, 0L,
                namedBlockStore.resolveActiveTasks(block.id, nextDate).withMeasuredDurations(blockLogStore))
        } ?: emptyList()
    }

    var plan by remember(date, refreshKey, localRefreshKey) {
        mutableStateOf(registry.planForDate(date, namedBlockInstances = blockInstances,
            floatingBlocks = floatingBlockInstances,
            nowMs = if (isToday) System.currentTimeMillis() else null))
    }
    var nextDayScheduled by remember(date, refreshKey, localRefreshKey) {
        mutableStateOf(registry.planForDate(date.plusDays(1), namedBlockInstances = nextDayBlockInstances,
            floatingBlocks = nextDayFloatingInstances).scheduled)
    }
    var nowMin by remember(viewStartMs) { mutableIntStateOf(minutesFromViewStart(viewStartMs)) }
    var calEvents by remember(date) { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    var calEventBlocks by remember(date, refreshKey) { mutableStateOf<Map<Long, Pair<Long, Long>>>(emptyMap()) }
    var reservingBlocks by remember(date, refreshKey) { mutableStateOf<List<Pair<Long, Long>>>(emptyList()) }

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("waypoint_timeline", android.content.Context.MODE_PRIVATE) }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    // Reading the persisted zoom synchronously here (instead of correcting it from a
    // LaunchedEffect after first composition) means the very first frame already shows the
    // right zoom level — nothing to animate away from on screen open.
    var zoomIndex by remember { mutableIntStateOf(prefs.getInt("zoom_index", 1)) }
    val zoomFactors = listOf(1f, 2.5f, 5f)
    val zoomLabels  = listOf("1×", "2.5×", "5×")
    // Animated in px (not a plain derived Dp) so a zoom change smoothly interpolates the whole
    // timeline's layout instead of snapping straight to the new scale — see the LaunchedEffect
    // below, which drives both this and the scroll offset together, frame by frame.
    var hourHeightPx by remember { mutableFloatStateOf(with(density) { (HOUR_HEIGHT * zoomFactors[zoomIndex]).toPx() }) }
    val hourHeight = with(density) { hourHeightPx.toDp() }
    // Quarter/minute sub-labels add hundreds of Text nodes at higher zoom levels; rendering
    // them on every frame of the height animation (below) is what made zooming feel laggy, so
    // they're hidden for the ~260ms of the transition and pop back in once it settles.
    var isAnimatingZoom by remember { mutableStateOf(false) }
    val showQuarterLabels = zoomIndex == 1 && !isAnimatingZoom
    val showMinuteLines   = zoomIndex == 2 && !isAnimatingZoom
    var anchorMinute by remember { mutableIntStateOf(-1) }

    LaunchedEffect(zoomIndex) {
        prefs.edit().putInt("zoom_index", zoomIndex).apply()
        val startHeightPx = hourHeightPx
        val targetHeightPx = with(density) { (HOUR_HEIGHT * zoomFactors[zoomIndex]).toPx() }
        if (startHeightPx == targetHeightPx) return@LaunchedEffect
        val startScrollPx = scrollState.value.toFloat()
        val targetScrollPx = if (anchorMinute >= 0)
            anchorMinute.coerceAtLeast(0) / 60f * targetHeightPx
        else null
        isAnimatingZoom = true
        try {
            animate(
                initialValue = startHeightPx,
                targetValue = targetHeightPx,
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
            ) { value, _ ->
                hourHeightPx = value
                if (targetScrollPx != null) {
                    val fraction = (value - startHeightPx) / (targetHeightPx - startHeightPx)
                    val desiredScroll = startScrollPx + (targetScrollPx - startScrollPx) * fraction
                    val delta = desiredScroll - scrollState.value
                    if (delta != 0f) scrollState.dispatchRawDelta(delta)
                }
            }
        } finally {
            isAnimatingZoom = false
        }
    }

    LaunchedEffect(date) {
        val scrollMin = if (isNowVisible) (nowMin - 60) else (4 * 60)
        val scrollPx = with(density) {
            scrollMin.coerceAtLeast(0) / 60f * hourHeight.toPx()
        }.toInt()
        scrollState.animateScrollTo(scrollPx)
    }

    // Extracted (rather than nested inside the LaunchedEffect below) so drag-commit handlers
    // can also call it directly after writing a calendar-event change, instead of waiting up
    // to 60s for the next periodic sync.
    suspend fun fetchAndSync() {
        if (calendarSignals?.hasPermission() == true) {
            val today   = calendarSignals.eventsForDate(date)
            val nextDay = calendarSignals.eventsForDate(date.plusDays(1))
            val combined = today + nextDay
            calEvents = combined
            val allCalBlocks = combined
                .filter { !it.allDay && it.title != "Sleep" }
                .associate { it.eventId to (it.startMillis to it.endMillis) }
            calEventBlocks = allCalBlocks
            val blocks = combined
                .filter { evt ->
                    !evt.allDay && evt.title != "Sleep" &&
                        (calendarPrefsStore == null || calendarPrefsStore.reservesTime(evt.eventId))
                }
                .map { it.startMillis to it.endMillis }
            reservingBlocks = blocks
            plan = registry.planForDate(date, calendarEventBlocks = allCalBlocks,
                reservingBlocks = blocks, namedBlockInstances = blockInstances,
                floatingBlocks = floatingBlockInstances,
                nowMs = if (isToday) System.currentTimeMillis() else null)
            nextDayScheduled = registry.planForDate(date.plusDays(1),
                calendarEventBlocks = allCalBlocks, reservingBlocks = blocks,
                namedBlockInstances = nextDayBlockInstances,
                floatingBlocks = nextDayFloatingInstances).scheduled
            onCalEventsChanged?.invoke(combined)
        }
    }

    // Commits a calendar-event drag/resize from the timeline. instanceStartMillis is passed
    // through so a recurring event only gets a single-occurrence exception, matching the
    // detail-sheet edit flow.
    fun commitCalendarEventDrag(evt: CalendarEvent, newStartMs: Long, newEndMs: Long) {
        val cal = calendarSignals ?: return
        scope.launch {
            cal.updateEvent(
                eventId = evt.eventId,
                title = evt.title,
                startMillis = newStartMs,
                endMillis = newEndMs,
                description = evt.description,
                allDay = evt.allDay,
                instanceStartMillis = evt.startMillis
            )
            fetchAndSync()
        }
    }

    // Commits a named-block drag/resize as a per-date schedule override — same mechanism as
    // the manual 14-day schedule editors, just written from a gesture instead of a picker.
    // Floating blocks are placed fresh by the planner on every replan (resolveForDate skips
    // them entirely), so a per-date override for one would never be read back — bail instead
    // of writing dead data. isResize distinguishes a pure move (start+end shift together,
    // duration preserved) from a resize (end changed independently) so moving a duration-mode
    // block (endHour == -1) doesn't silently pin it to a fixed end time.
    fun commitBlockDrag(blockId: String, originalStartMs: Long, newStartMs: Long, newEndMs: Long, isResize: Boolean) {
        val store = namedBlockStore ?: return
        val block = store.loadBlock(blockId) ?: return
        if (block.isFloating) return
        val originalDate = Instant.ofEpochMilli(originalStartMs).atZone(zone).toLocalDate()
        val startZdt = Instant.ofEpochMilli(newStartMs).atZone(zone)
        val endZdt = Instant.ofEpochMilli(newEndMs).atZone(zone)
        val newDate = startZdt.toLocalDate()
        // A drag that crosses midnight relocates the occurrence onto a new calendar date;
        // disable the old date's slot so a recurring block doesn't keep showing there too.
        if (newDate != originalDate) {
            store.setSchedule(NamedBlockSchedule(blockId = blockId, date = originalDate.toString(), enabled = false))
        }
        val preserveDurationMode = !isResize && block.useTotalTaskDuration
        store.setSchedule(
            NamedBlockSchedule(
                blockId = blockId,
                date = newDate.toString(),
                enabled = true,
                startHour = startZdt.hour,
                startMinute = startZdt.minute,
                endHour = if (preserveDurationMode) -1 else endZdt.hour,
                endMinute = if (preserveDurationMode) 0 else endZdt.minute
            )
        )
        localRefreshKey++
    }

    // Commits a floating-task drag. Unlike blocks/calendar events, a task has no per-date
    // time-pin storage — the scheduler always re-derives its placement from conditions — so a
    // drag is translated into afterTask/beforeTask constraints against whichever other floating
    // tasks it was dragged across, inferred from their current placement. A drag that doesn't
    // cross anything has no durable effect: there's nothing to encode a bare "move" as.
    fun commitTaskDrag(taskId: String, originalStartMs: Long, originalEndMs: Long, newStartMs: Long, newEndMs: Long) {
        val tm = taskManager ?: return
        val req = tm.getAllTasks().find { it.id == taskId } ?: return
        val others = plan.scheduled.filter {
            it.event.sourceWidgetId == TaskManagerScript.WIDGET_ID && it.event.id != taskId
        }
        val afterIds = (req.conditions.firstOrNull { it.type == "afterTask" }?.referenceTaskIds ?: emptyList()).toMutableSet()
        val beforeIds = (req.conditions.firstOrNull { it.type == "beforeTask" }?.referenceTaskIds ?: emptyList()).toMutableSet()
        var changed = false
        for (other in others) {
            val wasBefore = originalEndMs <= other.startMillis
            val wasAfter  = originalStartMs >= other.endMillis
            val nowBefore = newEndMs <= other.startMillis
            val nowAfter  = newStartMs >= other.endMillis
            if (wasBefore && nowAfter) {
                afterIds += other.event.id; beforeIds -= other.event.id; changed = true
            } else if (wasAfter && nowBefore) {
                beforeIds += other.event.id; afterIds -= other.event.id; changed = true
            }
        }
        if (!changed) return
        val newConditions = req.conditions.filter { it.type != "afterTask" && it.type != "beforeTask" } +
            listOfNotNull(
                if (afterIds.isNotEmpty()) TaskConditionSpec("afterTask", referenceTaskIds = afterIds.sorted()) else null,
                if (beforeIds.isNotEmpty()) TaskConditionSpec("beforeTask", referenceTaskIds = beforeIds.sorted()) else null
            )
        tm.submitTask(req.copy(conditions = newConditions))
        localRefreshKey++
    }

    // Keyed on localRefreshKey too — otherwise this loop keeps a closure over whatever
    // blockInstances were current when it first launched and silently reverts a drag/resize
    // commit up to 60s later, same staleness bug already fixed on the 5-second ticker below.
    LaunchedEffect(date, refreshKey, localRefreshKey) {
        fetchAndSync()
        while (true) { delay(60_000L); fetchAndSync() }
    }

    // 1-second ticker: keeps the now-indicator moving smoothly
    LaunchedEffect(viewStartMs, viewEndMs) {
        while (true) {
            delay(1_000L)
            val now = System.currentTimeMillis()
            isNowVisible = now in viewStartMs until viewEndMs
            nowMin = minutesFromViewStart(viewStartMs)
        }
    }

    // 5-second ticker: re-plans the day. Keyed on refreshKey/localRefreshKey too — otherwise
    // this loop keeps a closure over whatever blockInstances were current when it first
    // launched and can silently revert a drag/resize commit the next time it fires.
    LaunchedEffect(viewStartMs, refreshKey, localRefreshKey) {
        while (true) {
            delay(5_000L)
            val now = System.currentTimeMillis()
            val allCalBlocks = calEventBlocks
            val blocks = reservingBlocks
            plan = registry.planForDate(date, calendarEventBlocks = allCalBlocks,
                reservingBlocks = blocks, namedBlockInstances = blockInstances,
                floatingBlocks = floatingBlockInstances,
                nowMs = if (isToday) now else null)
            nextDayScheduled = registry.planForDate(date.plusDays(1),
                calendarEventBlocks = allCalBlocks, reservingBlocks = blocks,
                namedBlockInstances = nextDayBlockInstances,
                floatingBlocks = nextDayFloatingInstances).scheduled
        }
    }

    val outline        = MaterialTheme.colorScheme.outlineVariant
    val surface        = MaterialTheme.colorScheme.surface
    val onSV           = MaterialTheme.colorScheme.onSurfaceVariant
    val secCont        = MaterialTheme.colorScheme.secondaryContainer
    val terCont        = MaterialTheme.colorScheme.tertiaryContainer
    val onSecCont      = MaterialTheme.colorScheme.onSecondaryContainer
    val onTerCont      = MaterialTheme.colorScheme.onTertiaryContainer
    val indicatorColor = MaterialTheme.colorScheme.error

    val totalH = hourHeight * TOTAL_HOURS

    // pointerInput(Unit) below never restarts, so hourHeight (recomputed from zoomIndex on
    // every recomposition) has to be read through rememberUpdatedState — otherwise the pinch
    // handler would keep anchoring against whatever zoom level was active when the gesture
    // detector coroutine first launched.
    val latestHourHeightPx = rememberUpdatedState(with(density) { hourHeight.toPx() })

    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .pointerInput(Unit) {
                    // Pinch-to-zoom that locks onto the existing zoomFactors steps rather than
                    // scaling freely. Only engages once a second pointer joins the gesture, so
                    // an ordinary one-finger drag is left untouched for verticalScroll above.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var accumulatedZoom = 1f
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                val zoomChange = event.calculateZoom()
                                if (zoomChange != 1f) {
                                    accumulatedZoom *= zoomChange
                                    pressed.forEach { it.consume() }
                                    val centroidY = event.calculateCentroid(useCurrent = true).y
                                    fun jumpTo(newIndex: Int) {
                                        if (newIndex != zoomIndex) {
                                            val hourHeightPx = latestHourHeightPx.value
                                            anchorMinute = ((scrollState.value + centroidY) / hourHeightPx * 60).toInt()
                                            zoomIndex = newIndex
                                        }
                                        accumulatedZoom = 1f
                                    }
                                    when {
                                        accumulatedZoom > 1.25f -> jumpTo((zoomIndex + 1).coerceAtMost(zoomFactors.lastIndex))
                                        accumulatedZoom < 0.8f  -> jumpTo((zoomIndex - 1).coerceAtLeast(0))
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            Row(Modifier.fillMaxWidth().height(totalH)) {
                HourLabelsColumn(
                    viewStartMs = viewStartMs,
                    totalHours = TOTAL_HOURS,
                    hourHeight = hourHeight,
                    showQuarterLabels = showQuarterLabels,
                    showMinuteLines = showMinuteLines,
                    onSV = onSV
                )
                TimelineBody(
                    modifier = Modifier.weight(1f),
                    date = date,
                    viewStartMs = viewStartMs,
                    viewEndMs = viewEndMs,
                    totalHours = TOTAL_HOURS,
                    totalMinutes = TOTAL_HOURS * 60,
                    hourHeight = hourHeight,
                    showMinuteLines = showMinuteLines,
                    plan = plan,
                    nextDayScheduled = nextDayScheduled,
                    calEvents = calEvents,
                    blockInstances = blockInstances,
                    nextDayBlockInstances = nextDayBlockInstances,
                    nowMin = nowMin,
                    isNowVisible = isNowVisible,
                    isToday = isToday,
                    sleepSchedule = sleepSchedule,
                    activeBlockId = activeBlockId,
                    onBlockStart = onBlockStart,
                    outline = outline,
                    onSV = onSV,
                    secCont = secCont,
                    terCont = terCont,
                    onSecCont = onSecCont,
                    onTerCont = onTerCont,
                    indicatorColor = indicatorColor,
                    onCalendarEventClick = onCalendarEventClick,
                    onPlannerEventClick = onPlannerEventClick,
                    onFreeSlotClick = onFreeSlotClick,
                    onCalendarEventDrag = if (calendarSignals?.hasWritePermission() == true)
                        ::commitCalendarEventDrag else null,
                    onBlockDrag = if (namedBlockStore != null) ::commitBlockDrag else null,
                    onTaskDrag = if (taskManager != null) ::commitTaskDrag else null
                )
            }
        }
        // Zoom level pill — fixed overlay, does not scroll with the timeline.
        // Carries a magnifier icon (not just "1×" text) so it reads as interactive.
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 4.dp, end = 6.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(surface.copy(alpha = 0.95f))
                .border(1.dp, outline.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .clickable {
                    val topMinute = (scrollState.value / with(density) { hourHeight.toPx() } * 60).toInt()
                    anchorMinute = topMinute
                    zoomIndex = (zoomIndex + 1) % zoomFactors.size
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Change zoom level",
                tint = onSV,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = zoomLabels[zoomIndex],
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                color = onSV
            )
        }
    }
}

// ── Hour label column ─────────────────────────────────────────────────────────

@Composable
private fun HourLabelsColumn(
    viewStartMs: Long,
    totalHours: Int,
    hourHeight: Dp,
    showQuarterLabels: Boolean,
    showMinuteLines: Boolean,
    onSV: Color
) {
    Box(Modifier.width(LABEL_WIDTH).fillMaxHeight()) {
        for (h in 0..totalHours) {
            val yOff = (hourHeight * h - 8.dp).coerceAtLeast(2.dp)
            val wallHour = Calendar.getInstance().apply {
                timeInMillis = viewStartMs + h * 3600_000L
            }.get(Calendar.HOUR_OF_DAY)
            Text(
                text = "%02d:00".format(wallHour),
                modifier = Modifier.yOffset(yOff),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = onSV.copy(alpha = 0.38f)
            )
            if (showQuarterLabels && h < totalHours) {
                for (m in listOf(15, 30, 45)) {
                    val minYOff = (hourHeight * h + hourHeight * m / 60f - 6.dp).coerceAtLeast(2.dp)
                    Text(
                        text = ":%02d".format(m),
                        modifier = Modifier.yOffset(minYOff).padding(start = 4.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = onSV.copy(alpha = 0.38f)
                    )
                }
            }
            if (showMinuteLines && h < totalHours) {
                for (m in 5..55 step 5) {
                    val minYOff = (hourHeight * h + hourHeight * m / 60f - 6.dp).coerceAtLeast(2.dp)
                    val alpha = if (m % 15 == 0) 0.45f else 0.30f
                    Text(
                        text = ":%02d".format(m),
                        modifier = Modifier.yOffset(minYOff).padding(start = 4.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = onSV.copy(alpha = alpha)
                    )
                }
            }
        }
    }
}

// ── Timeline body ─────────────────────────────────────────────────────────────

@Composable
private fun TimelineBody(
    modifier: Modifier = Modifier,
    date: LocalDate,
    viewStartMs: Long,
    viewEndMs: Long,
    totalHours: Int,
    totalMinutes: Int,
    hourHeight: Dp,
    showMinuteLines: Boolean,
    plan: DayPlan,
    nextDayScheduled: List<ScheduledEvent>,
    calEvents: List<CalendarEvent>,
    blockInstances: List<NamedBlockInstance>,
    nextDayBlockInstances: List<NamedBlockInstance>,
    nowMin: Int,
    isNowVisible: Boolean,
    isToday: Boolean,
    sleepSchedule: SleepSchedule?,
    activeBlockId: String?,
    onBlockStart: ((blockId: String, scheduledEndMs: Long) -> Unit)?,
    outline: Color,
    onSV: Color,
    secCont: Color,
    terCont: Color,
    onSecCont: Color,
    onTerCont: Color,
    indicatorColor: Color,
    onCalendarEventClick: ((CalendarEvent) -> Unit)?,
    onPlannerEventClick: ((ScheduledEvent) -> Unit)?,
    onFreeSlotClick: ((startMs: Long, endMs: Long) -> Unit)?,
    onCalendarEventDrag: ((evt: CalendarEvent, newStartMs: Long, newEndMs: Long) -> Unit)? = null,
    onBlockDrag: ((blockId: String, originalStartMs: Long, newStartMs: Long, newEndMs: Long, isResize: Boolean) -> Unit)? = null,
    onTaskDrag: ((taskId: String, originalStartMs: Long, originalEndMs: Long, newStartMs: Long, newEndMs: Long) -> Unit)? = null
) {
    val viewTotalMin = totalMinutes

    // These derived lists depend only on the plan/calendar/block data below, never on
    // hourHeight — remembered so the zoom animation's ~60fps hourHeight changes don't force
    // re-sorting/re-filtering/re-grouping every frame (that per-frame rebuild was the main
    // source of zoom lag; only the Y-position math downstream actually needs to redo per frame).

    // Merge today's events with next-day events inside the 4AM-4AM window
    val mergedScheduled = remember(plan, nextDayScheduled, viewEndMs) {
        val combined = (plan.scheduled + nextDayScheduled.filter { it.startMillis < viewEndMs })
            .sortedBy { it.startMillis }
        val out = mutableListOf<ScheduledEvent>()
        for (se in combined) {
            val last = out.lastOrNull()
            if (last != null && last.endMillis == se.startMillis &&
                (last.event.id == se.event.id ||
                 (last.event.category == EventCategory.SLEEP && se.event.category == EventCategory.SLEEP))
            ) {
                out[out.size - 1] = last.copy(endMillis = se.endMillis)
            } else {
                out += se
            }
        }
        out
    }

    // DURING sub-tasks render inside the parent block tile.
    // BEFORE/AFTER sub-tasks render as standalone events at their actual timeline positions.
    val duringSubTaskIds: Set<String> = remember(blockInstances, nextDayBlockInstances) {
        (blockInstances + nextDayBlockInstances)
            .flatMap { inst -> inst.activeTasks.filter { it.placement == BlockTaskPlacement.DURING }.map { it.id } }
            .toSet()
    }
    val visibleScheduled = remember(mergedScheduled, duringSubTaskIds) {
        mergedScheduled.filter { se ->
            val sourceId = se.event.sourceWidgetId ?: return@filter true
            if (!sourceId.startsWith("__block__")) return@filter true
            se.event.id !in duringSubTaskIds
        }
    }

    // Compute free time windows (gaps ≥ 15 min between occupied ranges).
    // Use mergedScheduled (all events) so BEFORE/AFTER block tasks also count as occupied.
    val freeWindows = remember(mergedScheduled, calEvents, viewStartMs, viewTotalMin) {
        val raw = mutableListOf<Pair<Int, Int>>()
        mergedScheduled.forEach { se ->
            val s = msToMin(se.startMillis, viewStartMs)
            val e = msToMin(se.endMillis,   viewStartMs)
            if (e > s) raw += s to e
        }
        calEvents.filter { !it.allDay }.forEach { evt ->
            val s = msToMin(evt.startMillis, viewStartMs)
            val e = msToMin(evt.endMillis,   viewStartMs)
            if (e > s) raw += s to e
        }
        val sorted = raw.sortedBy { it.first }
        val merged = mutableListOf<Pair<Int, Int>>()
        for ((s, e) in sorted) {
            val last = merged.lastOrNull()
            if (last != null && s <= last.second)
                merged[merged.size - 1] = last.first to maxOf(last.second, e)
            else merged += s to e
        }
        val windows = mutableListOf<Pair<Int, Int>>()
        var cursor = 0
        for ((occStart, occEnd) in merged) {
            if (occStart >= viewTotalMin) break
            val gapEnd = occStart.coerceIn(0, viewTotalMin)
            if (gapEnd > cursor && gapEnd - cursor >= 15) windows += cursor to gapEnd
            if (occEnd > cursor) cursor = occEnd
        }
        if (viewTotalMin > cursor && viewTotalMin - cursor >= 15) windows += cursor to viewTotalMin
        windows
    }

    // Group DURING block sub-tasks by parent block id so we can render them inside the tile
    val blockSubTasksMap = remember(mergedScheduled, duringSubTaskIds) {
        mergedScheduled
            .filter { it.event.sourceWidgetId?.startsWith("__block__") == true && it.event.id in duringSubTaskIds }
            .groupBy { it.event.sourceWidgetId!!.removePrefix("__block__") }
    }

    Box(modifier.fillMaxHeight().clipToBounds()) {
        GridLines(hourHeight = hourHeight, totalHours = totalHours, showMinuteLines = showMinuteLines, outline = outline)

        // Free time windows
        freeWindows.forEach { (startMin, endMin) ->
            FreeWindowBlock(
                startMin = startMin,
                endMin = endMin,
                hourHeight = hourHeight,
                onSV = onSV,
                onClick = onFreeSlotClick?.let { cb -> {
                    cb(viewStartMs + startMin * 60_000L, viewStartMs + endMin * 60_000L)
                }}
            )
        }

        // Calendar event blocks (skip Sleep — handled by planner)
        calEvents.filter { !it.allDay && it.title != "Sleep" }.forEach { evt ->
            CalendarEventBlock(evt, viewStartMs, viewTotalMin, hourHeight, onCalendarEventClick, onCalendarEventDrag)
        }

        // Planner event blocks
        val eventColors = listOf(secCont to onSecCont, terCont to onTerCont)
        var habitIdx = 0
        visibleScheduled.forEach { se ->
            val colorPair = if (se.event.category == EventCategory.SLEEP ||
                                se.event.category == EventCategory.BLOCK) null
                            else eventColors[habitIdx++ % eventColors.size]
            val subTasks = if (se.event.category == EventCategory.BLOCK)
                blockSubTasksMap[se.event.id.removePrefix("__block__")] ?: emptyList()
            else emptyList()
            PlannerEventBlock(
                se = se,
                viewStartMs = viewStartMs,
                hourHeight = hourHeight,
                blockInstances = blockInstances,
                nextDayBlockInstances = nextDayBlockInstances,
                defaultColorPair = colorPair,
                blockSubTasks = subTasks,
                isToday = isToday,
                activeBlockId = activeBlockId,
                onBlockStart = onBlockStart,
                onPlannerEventClick = onPlannerEventClick,
                onBlockDrag = onBlockDrag,
                onTaskDrag = onTaskDrag
            )
        }

        // Alarm markers for planned sleep
        AlarmMarkersSection(
            mergedScheduled = visibleScheduled,
            viewStartMs = viewStartMs,
            totalMinutes = totalMinutes,
            hourHeight = hourHeight,
            sleepSchedule = sleepSchedule,
            date = date,
            blockInstances = blockInstances,
            nextDayBlockInstances = nextDayBlockInstances
        )

        // Current-time indicator
        if (isNowVisible) {
            val clampedNow = nowMin.coerceIn(0, totalMinutes)
            val nowY = minToY(clampedNow, hourHeight)
            val nowLabel = remember(nowMin) {
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(viewStartMs + clampedNow * 60_000L))
            }
            Text(
                text = nowLabel,
                color = indicatorColor,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                modifier = Modifier
                    .yOffset(nowY - 20.dp)
                    .padding(start = 2.dp)
            )
            Canvas(Modifier.yOffset(nowY - 4.dp).fillMaxWidth().height(8.dp)) {
                val cy = size.height / 2f
                drawCircle(indicatorColor, 4.dp.toPx(), Offset(0f, cy))
                drawLine(indicatorColor, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1.5.dp.toPx())
            }
        }
    }
}

// ── Per-element composables ────────────────────────────────────────────────────

@Composable
private fun GridLines(hourHeight: Dp, totalHours: Int, showMinuteLines: Boolean, outline: Color) {
    Canvas(Modifier.fillMaxSize()) {
        for (h in 0..totalHours) {
            val y = h * hourHeight.toPx()
            drawLine(color = outline, start = Offset(0f, y), end = Offset(size.width, y),
                strokeWidth = 0.5.dp.toPx())
            if (h < totalHours) {
                if (showMinuteLines) {
                    for (m in 1..59) {
                        if (m % 15 == 0) continue
                        val my = y + m * hourHeight.toPx() / 60f
                        drawLine(color = outline.copy(alpha = 0.38f),
                            start = Offset(0f, my), end = Offset(size.width, my),
                            strokeWidth = 0.4.dp.toPx())
                    }
                }
                for (q in 1..3) {
                    val qy = y + q * hourHeight.toPx() / 4f
                    drawLine(color = outline.copy(alpha = 0.65f),
                        start = Offset(0f, qy), end = Offset(size.width, qy),
                        strokeWidth = 0.45.dp.toPx())
                }
            }
        }
    }
}

@Composable
private fun FreeWindowBlock(
    startMin: Int,
    endMin: Int,
    hourHeight: Dp,
    onSV: Color,
    onClick: (() -> Unit)? = null
) {
    val startY = minToY(startMin, hourHeight)
    val blockH = (minToY(endMin, hourHeight) - startY).coerceAtLeast(4.dp)
    val durMin = endMin - startMin
    val h = durMin / 60; val m = durMin % 60
    val durLabel = when {
        durMin >= 60 && m > 0 -> "${h}h ${m}m"
        durMin >= 60           -> "${h}h"
        else                   -> "${durMin}m"
    }
    // Free slots are the fastest way to add something (tap opens +Task/+Block/+Event),
    // so they need to read as tappable rather than as empty background.
    Box(
        Modifier
            .yOffset(startY)
            .fillMaxWidth()
            .height(blockH)
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(onSV.copy(alpha = 0.09f))
            .border(1.dp, onSV.copy(alpha = 0.24f), RoundedCornerShape(4.dp))
    ) {
        if (blockH >= 20.dp) {
            Text(
                text = if (durMin >= 30) "+ free · $durLabel" else "free · $durLabel",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                color = onSV.copy(alpha = 0.55f)
            )
        }
    }
}

@Composable
private fun CalendarEventBlock(
    evt: CalendarEvent,
    viewStartMs: Long,
    viewTotalMin: Int,
    hourHeight: Dp,
    onCalendarEventClick: ((CalendarEvent) -> Unit)?,
    onCalendarEventDrag: ((evt: CalendarEvent, newStartMs: Long, newEndMs: Long) -> Unit)? = null
) {
    val ceStartMin = msToMin(evt.startMillis, viewStartMs)
    val ceEndMin   = msToMin(evt.endMillis,   viewStartMs)
    if (ceStartMin >= viewTotalMin || ceEndMin <= 0) return
    val calColor = if (evt.calendarColor != 0) Color(evt.calendarColor)
                   else MaterialTheme.colorScheme.primary

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val latestHourHeightPx = rememberUpdatedState(with(density) { hourHeight.toPx() })
    // pointerInput(evt.eventId) below only relaunches when the event identity changes, never
    // when its time does (the id is stable across a drag), so onDragEnd must read evt through
    // rememberUpdatedState — otherwise a second drag on the same event commits against the
    // time captured on first composition instead of wherever it currently sits.
    val latestEvt = rememberUpdatedState(evt)
    var isDragging by remember { mutableStateOf(false) }
    var moveOffsetMin by remember { mutableIntStateOf(0) }
    var resizeOffsetMin by remember { mutableIntStateOf(0) }
    var moveAccumPx by remember { mutableStateOf(0f) }
    val draggable = onCalendarEventDrag != null
    val minDurationOffset = 5 - (ceEndMin - ceStartMin)

    val previewStartMin = ceStartMin + moveOffsetMin
    val previewEndMin = ceEndMin + moveOffsetMin + resizeOffsetMin
    val startY  = minToY(previewStartMin, hourHeight)
    val eventH  = (minToY(previewEndMin, hourHeight) - startY - 2.dp).coerceAtLeast(24.dp)

    Box(
        Modifier
            .yOffset(startY + 1.dp)
            .fillMaxWidth()
            .height(eventH)
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(if (onCalendarEventClick != null) Modifier.clickable { onCalendarEventClick(evt) } else Modifier)
            .then(if (draggable) Modifier.pointerInput(evt.eventId) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        moveAccumPx = 0f
                        isDragging = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragEnd = {
                        isDragging = false
                        val delta = snapMinutesDelta(moveAccumPx / latestHourHeightPx.value * 60f)
                        moveAccumPx = 0f
                        moveOffsetMin = 0
                        val current = latestEvt.value
                        if (delta != 0) {
                            onCalendarEventDrag?.invoke(
                                current, current.startMillis + delta * 60_000L, current.endMillis + delta * 60_000L
                            )
                        }
                    },
                    onDragCancel = { isDragging = false; moveAccumPx = 0f; moveOffsetMin = 0 },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        moveAccumPx += dragAmount.y
                        moveOffsetMin = snapMinutesDelta(moveAccumPx / latestHourHeightPx.value * 60f)
                    }
                )
            } else Modifier)
            .background(calColor.copy(alpha = if (isDragging) 0.22f else 0.13f))
            .border(if (isDragging) 2.dp else 1.dp, calColor.copy(alpha = if (isDragging) 0.7f else 0.38f), RoundedCornerShape(6.dp))
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                evt.title,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                fontWeight = FontWeight.SemiBold,
                color = calColor.copy(alpha = 0.85f),
                maxLines = 1
            )
            if (eventH >= 36.dp) {
                Text(
                    if (isDragging)
                        "${fmtMs(viewStartMs + previewStartMin * 60_000L)} – ${fmtMs(viewStartMs + previewEndMin * 60_000L)}"
                    else
                        "${fmtMs(evt.startMillis)} – ${fmtMs(evt.endMillis)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = calColor.copy(alpha = 0.55f)
                )
            }
        }
        if (draggable) {
            ResizeHandle(
                accent = calColor,
                hourHeight = hourHeight,
                minOffset = minDurationOffset,
                onResizePreview = { resizeOffsetMin = it },
                onResizeCommit = { delta ->
                    resizeOffsetMin = 0
                    onCalendarEventDrag?.invoke(evt, evt.startMillis, evt.endMillis + delta * 60_000L)
                }
            )
        }
    }
}

@Composable
private fun PlannerEventBlock(
    se: ScheduledEvent,
    viewStartMs: Long,
    hourHeight: Dp,
    blockInstances: List<NamedBlockInstance>,
    nextDayBlockInstances: List<NamedBlockInstance>,
    defaultColorPair: Pair<Color, Color>?,
    blockSubTasks: List<ScheduledEvent> = emptyList(),
    isToday: Boolean,
    activeBlockId: String?,
    onBlockStart: ((blockId: String, scheduledEndMs: Long) -> Unit)?,
    onPlannerEventClick: ((ScheduledEvent) -> Unit)?,
    onBlockDrag: ((blockId: String, originalStartMs: Long, newStartMs: Long, newEndMs: Long, isResize: Boolean) -> Unit)? = null,
    onTaskDrag: ((taskId: String, originalStartMs: Long, originalEndMs: Long, newStartMs: Long, newEndMs: Long) -> Unit)? = null
) {
    val isSleep       = se.event.category == EventCategory.SLEEP
    val isLoggedSleep = isSleep && se.event.isLogged
    val isBlock       = se.event.category == EventCategory.BLOCK
    val seStartMin = msToMin(se.startMillis, viewStartMs)
    val seEndMin   = msToMin(se.endMillis,   viewStartMs)

    // Fixed (non-floating) blocks only: a floating block is re-placed by the planner on every
    // replan, so a dragged position for one can never be persisted — don't offer the gesture.
    val blockInstance = if (isBlock) {
        val blockId = se.event.id.removePrefix("__block__")
        blockInstances.find { it.block.id == blockId } ?: nextDayBlockInstances.find { it.block.id == blockId }
    } else null

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val latestHourHeightPx = rememberUpdatedState(with(density) { hourHeight.toPx() })
    // pointerInput(se.event.id) below only relaunches when the block/event identity changes,
    // never when its position does (the id is stable across a drag), so onDragEnd must read se
    // through rememberUpdatedState — otherwise a second drag on the same item commits against
    // the position captured on first composition instead of wherever it currently sits.
    val latestSe = rememberUpdatedState(se)
    var isDragging by remember { mutableStateOf(false) }
    var moveOffsetMin by remember { mutableIntStateOf(0) }
    var resizeOffsetMin by remember { mutableIntStateOf(0) }
    var moveAccumPx by remember { mutableStateOf(0f) }
    // A plain floating task (task_manager-sourced, not a block/block-subtask/sleep window) can
    // be moved but not resized here — resizing changes its duration, a different, more
    // consequential edit than reordering, so it stays behind AddTaskSheet for now.
    val isPlainTask = !isSleep && !isBlock && se.event.sourceWidgetId == TaskManagerScript.WIDGET_ID
    val blockDraggable = isBlock && onBlockDrag != null && blockInstance?.block?.isFloating != true
    val taskDraggable = isPlainTask && onTaskDrag != null
    val moveDraggable = blockDraggable || taskDraggable
    val resizeDraggable = blockDraggable
    val minDurationOffset = 5 - (seEndMin - seStartMin)

    val previewStartMin = seStartMin + moveOffsetMin
    val previewEndMin = seEndMin + moveOffsetMin + resizeOffsetMin
    val startY = minToY(previewStartMin, hourHeight)
    val eventH = (minToY(previewEndMin, hourHeight) - startY - 2.dp).coerceAtLeast(24.dp)

    val sleepAccent = MaterialTheme.colorScheme.tertiary
    val blockAccent: Color? = if (isBlock) {
        blockInstance?.block?.colorArgb?.let { Color(it) } ?: Color(0xFF4DB6AC)
    } else null

    val taskAccent: Color? = if (!isSleep && !isBlock && se.event.colorArgb != null)
        Color(se.event.colorArgb) else null

    val (bg, fg) = when {
        isLoggedSleep -> sleepAccent.copy(alpha = 0.18f) to sleepAccent
        isSleep       -> sleepAccent.copy(alpha = 0.07f) to sleepAccent.copy(alpha = 0.50f)
        isBlock       -> blockAccent!!.copy(alpha = 0.15f) to blockAccent
        taskAccent != null -> taskAccent.copy(alpha = 0.15f) to taskAccent
        else          -> defaultColorPair ?: (Color(0xFF4DB6AC).copy(alpha = 0.15f) to Color(0xFF4DB6AC))
    }
    val displayTitle = when {
        isLoggedSleep -> se.event.title
        isSleep       -> "${se.event.title} · planned"
        else          -> se.event.title
    }
    val borderMod = when {
        isLoggedSleep -> Modifier.border(1.dp, sleepAccent.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
        isSleep       -> Modifier.border(1.dp, sleepAccent.copy(alpha = 0.22f), RoundedCornerShape(6.dp))
        isBlock       -> Modifier.border(1.5.dp, blockAccent!!.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
        else          -> Modifier
    }

    val rawBlockId = if (isBlock) se.event.id.removePrefix("__block__") else null
    val showStartButton = isBlock && isToday && onBlockStart != null && rawBlockId != activeBlockId

    Box(
        Modifier
            .yOffset(startY + 1.dp)
            .fillMaxWidth()
            .height(eventH)
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(if (onPlannerEventClick != null) Modifier.clickable { onPlannerEventClick(se) } else Modifier)
            .then(if (moveDraggable) Modifier.pointerInput(se.event.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        moveAccumPx = 0f
                        isDragging = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragEnd = {
                        isDragging = false
                        val delta = snapMinutesDelta(moveAccumPx / latestHourHeightPx.value * 60f)
                        moveAccumPx = 0f
                        moveOffsetMin = 0
                        val current = latestSe.value
                        if (delta != 0) {
                            if (isBlock) {
                                val blockId = current.event.id.removePrefix("__block__")
                                onBlockDrag?.invoke(
                                    blockId, current.startMillis, current.startMillis + delta * 60_000L, current.endMillis + delta * 60_000L, false
                                )
                            } else {
                                onTaskDrag?.invoke(
                                    current.event.id, current.startMillis, current.endMillis,
                                    current.startMillis + delta * 60_000L, current.endMillis + delta * 60_000L
                                )
                            }
                        }
                    },
                    onDragCancel = { isDragging = false; moveAccumPx = 0f; moveOffsetMin = 0 },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        moveAccumPx += dragAmount.y
                        moveOffsetMin = snapMinutesDelta(moveAccumPx / latestHourHeightPx.value * 60f)
                    }
                )
            } else Modifier)
            .background(bg)
            .then(borderMod)
            .then(if (isDragging) Modifier.border(2.dp, fg.copy(alpha = 0.8f), RoundedCornerShape(6.dp)) else Modifier)
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                displayTitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                fontWeight = if (isLoggedSleep) FontWeight.SemiBold else FontWeight.Normal,
                color = fg,
                maxLines = 1
            )
            if (eventH >= 36.dp) {
                Text(
                    if (isDragging)
                        "${fmtMs(viewStartMs + previewStartMin * 60_000L)} – ${fmtMs(viewStartMs + previewEndMin * 60_000L)}"
                    else
                        "${fmtMs(se.startMillis)} – ${fmtMs(se.endMillis)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = fg.copy(alpha = 0.65f)
                )
            }
            if (isBlock && blockSubTasks.isNotEmpty() && eventH >= 56.dp) {
                blockSubTasks.sortedBy { it.startMillis }.forEach { task ->
                    val durMin = ((task.endMillis - task.startMillis) / 60_000L).toInt()
                    val durLabel = if (durMin < 60) "${durMin}m"
                        else "${durMin / 60}h${if (durMin % 60 > 0) " ${durMin % 60}m" else ""}"
                    Text(
                        "· ${fmtMs(task.startMillis)}  ${task.event.title}  $durLabel",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = fg.copy(alpha = 0.75f),
                        maxLines = 1
                    )
                }
            }
        }
        if (showStartButton) {
            Box(Modifier.align(Alignment.TopEnd).padding(2.dp)) {
                androidx.compose.material3.TextButton(
                    onClick = { onBlockStart!!(rawBlockId!!, se.endMillis) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "▶ Start",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = fg
                    )
                }
            }
        }
        if (resizeDraggable) {
            ResizeHandle(
                accent = blockAccent ?: sleepAccent,
                hourHeight = hourHeight,
                minOffset = minDurationOffset,
                onResizePreview = { resizeOffsetMin = it },
                onResizeCommit = { delta ->
                    resizeOffsetMin = 0
                    val blockId = se.event.id.removePrefix("__block__")
                    onBlockDrag?.invoke(blockId, se.startMillis, se.startMillis, se.endMillis + delta * 60_000L, true)
                }
            )
        }
    }
}

/**
 * Grab bar pinned to the bottom edge of a timeline tile. Dragging it changes only the tile's
 * end time (duration) — a plain drag, no long-press needed, since it's a small dedicated hit
 * area that doesn't compete with the tile body's tap-to-open/long-press-to-move gestures.
 * [minOffset] is the smallest (most negative) delta allowed, so a resize can't shrink the
 * tile below a 5-minute minimum duration.
 */
@Composable
private fun BoxScope.ResizeHandle(
    accent: Color,
    hourHeight: Dp,
    minOffset: Int,
    onResizePreview: (deltaMinutes: Int) -> Unit,
    onResizeCommit: (deltaMinutes: Int) -> Unit
) {
    val density = LocalDensity.current
    val latestHourHeightPx = rememberUpdatedState(with(density) { hourHeight.toPx() })
    val latestMinOffset = rememberUpdatedState(minOffset)
    // pointerInput(Unit) below launches its gesture-detection coroutine exactly once and never
    // relaunches, so it must read every value that can change (including the callbacks
    // themselves) through rememberUpdatedState — otherwise a resize after the item has ever
    // moved commits against a stale se/evt captured back on the first composition.
    val latestOnResizePreview = rememberUpdatedState(onResizePreview)
    val latestOnResizeCommit = rememberUpdatedState(onResizeCommit)
    var accumPx by remember { mutableStateOf(0f) }
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(18.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { accumPx = 0f },
                    onDragEnd = {
                        val delta = snapMinutesDelta(accumPx / latestHourHeightPx.value * 60f).coerceAtLeast(latestMinOffset.value)
                        accumPx = 0f
                        latestOnResizePreview.value(0)
                        if (delta != 0) latestOnResizeCommit.value(delta)
                    },
                    onDragCancel = { accumPx = 0f; latestOnResizePreview.value(0) },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accumPx += dragAmount.y
                        latestOnResizePreview.value(snapMinutesDelta(accumPx / latestHourHeightPx.value * 60f).coerceAtLeast(latestMinOffset.value))
                    }
                )
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            Modifier
                .padding(bottom = 2.dp)
                .width(24.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent.copy(alpha = 0.6f))
        )
    }
}

private data class AlarmMarkerPoint(val epochMs: Long, val label: String, val emphasized: Boolean)

@Composable
private fun AlarmMarkersSection(
    mergedScheduled: List<ScheduledEvent>,
    viewStartMs: Long,
    totalMinutes: Int,
    hourHeight: Dp,
    sleepSchedule: SleepSchedule?,
    date: LocalDate,
    blockInstances: List<NamedBlockInstance>,
    nextDayBlockInstances: List<NamedBlockInstance>
) {
    val sleepBlock = mergedScheduled.firstOrNull {
        it.event.category == EventCategory.SLEEP && !it.event.isLogged
    } ?: return
    val s = sleepSchedule ?: return
    val intervalMs = s.wakeAlarmIntervalMinutes * 60_000L

    // A block-synced alarm's real on/off state depends on whether its linked block
    // is scheduled on the calendar day the alarm actually falls on — not on whatever
    // AlarmBlockSync last computed for "today". Resolve per-marker against the block
    // instances already loaded for this viewed date and the next.
    val zone = ZoneId.systemDefault()
    val blockIdsOnDate = blockInstances.map { it.block.id }.toSet()
    val blockIdsNextDay = nextDayBlockInstances.map { it.block.id }.toSet()
    fun isActive(manualEnabled: Boolean, sync: SleepAlarmSync, epochMs: Long): Boolean {
        if (!sync.blockSyncEnabled || sync.linkedBlockId == null) return manualEnabled
        val markerDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val idsForDate = when (markerDate) {
            date -> blockIdsOnDate
            date.plusDays(1) -> blockIdsNextDay
            else -> emptySet()
        }
        return sync.linkedBlockId in idsForDate
    }

    // Only alarms that are actually active on this date (respects manual toggles
    // and block-sync resolved against this date's own schedule) get a marker.
    val alarmPoints = buildList {
        val preSleepMs = sleepBlock.startMillis - s.preSleepReminderMinutes * 60_000L
        if (s.preSleepReminderMinutes > 0 && isActive(s.preSleepAlarmEnabled, s.preSleepSync, preSleepMs)) {
            add(AlarmMarkerPoint(preSleepMs, "Pre-sleep", false))
        }
        if (isActive(s.bedtimeAlarmEnabled, s.bedtimeSync, sleepBlock.startMillis)) {
            add(AlarmMarkerPoint(sleepBlock.startMillis, "Bedtime", false))
        }
        val gentleMs = sleepBlock.endMillis - 2 * intervalMs
        if (s.wakeAlarmCount >= 3 && isActive(s.gentleWakeEnabled, s.gentleWakeSync, gentleMs)) {
            add(AlarmMarkerPoint(gentleMs, "Gentle wake", false))
        }
        val mediumMs = sleepBlock.endMillis - intervalMs
        if (s.wakeAlarmCount >= 2 && isActive(s.mediumWakeEnabled, s.mediumWakeSync, mediumMs)) {
            add(AlarmMarkerPoint(mediumMs, "Medium wake", false))
        }
        if (isActive(s.wakeAlarmEnabled, s.wakeUpSync, sleepBlock.endMillis)) {
            add(AlarmMarkerPoint(sleepBlock.endMillis, "Wake up", true))
        }
    }
    if (alarmPoints.isEmpty()) return

    val alarmAccent = MaterialTheme.colorScheme.primary

    alarmPoints.forEach { point ->
        val alarmMin = msToMin(point.epochMs, viewStartMs)
        if (alarmMin !in 0..totalMinutes) return@forEach
        val alarmY = minToY(alarmMin, hourHeight)
        val lineAlpha = if (point.emphasized) 0.55f else 0.32f
        val dotAlpha = if (point.emphasized) 0.95f else 0.6f

        Canvas(Modifier.yOffset(alarmY - 3.dp).fillMaxWidth().height(6.dp)) {
            val cy = size.height / 2f
            drawCircle(alarmAccent.copy(alpha = dotAlpha), 2.6.dp.toPx(), Offset(0f, cy))
            drawLine(
                color = alarmAccent.copy(alpha = lineAlpha),
                start = Offset(7.dp.toPx(), cy),
                end = Offset(size.width, cy),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
            )
        }
        Row(
            Modifier.yOffset(alarmY - 10.dp).fillMaxWidth().padding(end = 6.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(alarmAccent.copy(alpha = if (point.emphasized) 0.16f else 0.09f))
                    .border(
                        1.dp,
                        alarmAccent.copy(alpha = if (point.emphasized) 0.45f else 0.24f),
                        RoundedCornerShape(7.dp)
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "🔔",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp)
                    )
                    Text(
                        point.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = if (point.emphasized) FontWeight.SemiBold else FontWeight.Medium
                        ),
                        color = alarmAccent.copy(alpha = if (point.emphasized) 1f else 0.85f)
                    )
                    Text(
                        fmtMs(point.epochMs),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = alarmAccent.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}
