package com.waypoint.app.planner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.waypoint.app.home.BLOCK_COLORS
import com.waypoint.app.ui.components.HsvColorPicker
import com.waypoint.app.ui.components.toOpaqueColor
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import kotlinx.coroutines.delay

private fun List<BlockTask>.withMeasuredDurations(logStore: BlockSessionLogStore?): List<BlockTask> {
    if (logStore == null) return this
    return map { task ->
        if (task.useMeasuredDuration) {
            val avg = logStore.averageMeasuredMinutes(task.id)
            if (avg != null) task.copy(durationMinutes = avg) else task
        } else task
    }
}

private val BS_HOUR_HEIGHT = 90.dp
private val BS_LABEL_WIDTH = 44.dp

private fun bsMinToY(minutes: Int, hourHeight: Dp): Dp =
    hourHeight * (minutes.coerceAtLeast(0) / 60f)

private fun bsMsToMin(ms: Long, viewStartMs: Long): Int =
    ((ms - viewStartMs) / 60_000L).toInt()

private fun bsFmt(ms: Long): String =
    Calendar.getInstance().apply { timeInMillis = ms }
        .let { "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }

private fun Modifier.bsYOffset(y: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, y.roundToPx()) }
}

@Composable
fun BlockScopeView(
    session: ActiveBlockSession,
    registry: EventPlannerRegistry,
    namedBlockStore: NamedBlockStore,
    blockLogStore: BlockSessionLogStore? = null,
    date: LocalDate,
    refreshKey: Int = 0,
    isPlanningMode: Boolean = false,
    modifier: Modifier = Modifier,
    onEndSession: () -> Unit,
    onExitScope: (() -> Unit)? = null,
    onTaskClick: ((ScheduledEvent) -> Unit)? = null,
    onEditTask: ((BlockTask) -> Unit)? = null,
    onColorChanged: ((Int?) -> Unit)? = null,
    onFreeSlotClick: ((startMs: Long, endMs: Long) -> Unit)? = null
) {
    var blockColorArgb by remember { mutableStateOf(session.colorArgb) }
    val blockColor = blockColorArgb?.toOpaqueColor() ?: MaterialTheme.colorScheme.primary
    var showColorPicker by remember { mutableStateOf(false) }

    // Zoom — same tap-to-cycle affordance and preference key namespace as the main Plan-tab
    // timeline (DayTimelineView), just a separate stored index since this view's base hour
    // height and typical content density differ.
    val zoomContext = LocalContext.current
    val zoomPrefs = remember { zoomContext.getSharedPreferences("waypoint_timeline", android.content.Context.MODE_PRIVATE) }
    val zoomFactors = listOf(1f, 1.5f, 2.2f)
    val zoomLabels = listOf("1×", "1.5×", "2.2×")
    var zoomIndex by remember { mutableIntStateOf(zoomPrefs.getInt("bs_zoom_index", 0)) }
    LaunchedEffect(zoomIndex) { zoomPrefs.edit().putInt("bs_zoom_index", zoomIndex).apply() }
    val hourHeight = BS_HOUR_HEIGHT * zoomFactors[zoomIndex]

    val blockInstances = remember(date, refreshKey) {
        namedBlockStore.resolveForDate(date).map { (block, sched) ->
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
        }
    }
    val floatingInstances = remember(date, refreshKey) {
        namedBlockStore.loadAllBlocks().filter { it.isFloating }.map { block ->
            NamedBlockInstance(block, 0L, 0L,
                namedBlockStore.resolveActiveTasks(block.id, date).withMeasuredDurations(blockLogStore))
        }
    }

    var plan by remember(date, refreshKey) {
        mutableStateOf(
            registry.planForDate(
                date,
                namedBlockInstances = blockInstances,
                floatingBlocks = floatingInstances,
                nowMs = System.currentTimeMillis()
            )
        )
    }

    // Scheduled block start from the plan; fall back to session.startedAtMs
    val thisBlockInstance = blockInstances.find { it.block.id == session.blockId }
    val scheduledStartMs = thisBlockInstance?.scheduledStartMs ?: session.startedAtMs
    val scheduledEndMs   = thisBlockInstance?.estimatedEndMs   ?: session.scheduledEndMs

    // Sub-tasks for this block only
    val blockSubTasks = plan.scheduled.filter {
        it.event.sourceWidgetId == "__block__${session.blockId}"
    }

    // View window covers the block window plus any tasks scheduled outside it (BEFORE/AFTER)
    val viewStartMs = minOf(
        scheduledStartMs,
        blockSubTasks.minOfOrNull { it.startMillis } ?: scheduledStartMs
    )
    val viewEndMs = maxOf(
        scheduledEndMs,
        blockSubTasks.maxOfOrNull { it.endMillis } ?: scheduledEndMs
    )
    val totalMinutes = ((viewEndMs - viewStartMs) / 60_000L).toInt().coerceAtLeast(60)
    val totalHours   = (totalMinutes + 59) / 60

    var nowMin by remember { mutableIntStateOf(bsMsToMin(System.currentTimeMillis(), viewStartMs)) }
    var isNowVisible by remember(viewStartMs, viewEndMs) {
        mutableStateOf(System.currentTimeMillis() in viewStartMs until viewEndMs)
    }

    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    LaunchedEffect(viewStartMs, hourHeight) {
        val targetMin = if (isNowVisible) (nowMin - 30).coerceAtLeast(0) else 0
        scrollState.animateScrollTo(with(density) { (targetMin / 60f * hourHeight.toPx()).toInt() })
    }

    // 1-second ticker: keeps the now-indicator moving smoothly
    LaunchedEffect(viewStartMs, viewEndMs) {
        while (true) {
            delay(1_000L)
            val now = System.currentTimeMillis()
            nowMin = bsMsToMin(now, viewStartMs)
            isNowVisible = now in viewStartMs until viewEndMs
        }
    }

    // 5-second ticker: re-plans the day
    LaunchedEffect(viewStartMs, date, refreshKey) {
        while (true) {
            delay(5_000L)
            plan = registry.planForDate(
                date,
                namedBlockInstances = blockInstances,
                floatingBlocks = floatingInstances,
                nowMs = System.currentTimeMillis()
            )
        }
    }

    val outline      = MaterialTheme.colorScheme.outlineVariant
    val onSV         = MaterialTheme.colorScheme.onSurfaceVariant
    val secCont      = MaterialTheme.colorScheme.secondaryContainer
    val onSecCont    = MaterialTheme.colorScheme.onSecondaryContainer
    // Tile colors: use block color when one is set, otherwise fall back to Material defaults
    val taskTileBg = if (blockColorArgb != null) blockColor.copy(alpha = 0.18f) else secCont.copy(alpha = 0.35f)
    val taskTileFg = if (blockColorArgb != null) blockColor else onSecCont

    if (showColorPicker) {
        BsColorPickerDialog(
            currentArgb = blockColorArgb,
            onColorSelected = { argb ->
                blockColorArgb = argb
                val block = namedBlockStore.loadBlock(session.blockId)
                if (block != null) namedBlockStore.saveBlock(block.copy(colorArgb = argb))
                onColorChanged?.invoke(argb)
            },
            onClearColor = {
                blockColorArgb = null
                val block = namedBlockStore.loadBlock(session.blockId)
                if (block != null) namedBlockStore.saveBlock(block.copy(colorArgb = null))
                onColorChanged?.invoke(null)
            },
            onDismiss = { showColorPicker = false }
        )
    }

    Column(modifier.fillMaxSize()) {
        BsHeader(
            session = session,
            blockColor = blockColor,
            isPlanningMode = isPlanningMode,
            onColorPick = { showColorPicker = true },
            onEndSession = onEndSession,
            onExitScope = onExitScope
        )
        HorizontalDivider(color = blockColor.copy(alpha = 0.25f))

        val totalH = hourHeight * totalHours
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                Row(Modifier.fillMaxWidth().height(totalH)) {
                    BsHourLabels(viewStartMs, totalHours, onSV, hourHeight)
                    BsTimelineBody(
                        modifier = Modifier.weight(1f),
                        viewStartMs = viewStartMs,
                        totalHours = totalHours,
                        totalMinutes = totalMinutes,
                        hourHeight = hourHeight,
                        scheduledStartMs = scheduledStartMs,
                        scheduledEndMs = scheduledEndMs,
                        blockSubTasks = blockSubTasks,
                        nowMin = nowMin,
                        isNowVisible = isNowVisible,
                        blockColor = blockColor,
                        taskTileBg = taskTileBg,
                        taskTileFg = taskTileFg,
                        outline = outline,
                        onSV = onSV,
                        onTaskClick = onTaskClick,
                        onColorPick = if (onEditTask != null) ({ se ->
                            val task = namedBlockStore.loadTask(se.event.id)
                            if (task != null) onEditTask(task)
                        }) else null,
                        onFreeSlotClick = onFreeSlotClick?.let { cb ->
                            { startMin: Int, endMin: Int ->
                                cb(viewStartMs + startMin * 60_000L, viewStartMs + endMin * 60_000L)
                            }
                        }
                    )
                }
            }
            // Carries a magnifier icon (not just "1×" text) so it reads as interactive —
            // mirrors DayTimelineView's own zoom control for a consistent affordance.
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                    .border(1.dp, outline.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .clickable { zoomIndex = (zoomIndex + 1) % zoomFactors.size }
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
}

@Composable
private fun BsHeader(
    session: ActiveBlockSession,
    blockColor: Color,
    isPlanningMode: Boolean = false,
    onColorPick: () -> Unit,
    onEndSession: () -> Unit,
    onExitScope: (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(blockColor)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (isPlanningMode) "Planning: ${session.blockName}" else session.blockName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = blockColor
            )
            Text(
                text = "${bsFmt(session.startedAtMs)} – ${bsFmt(session.scheduledEndMs)}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(blockColor)
                .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .clickable(onClick = onColorPick)
        )
        Spacer(Modifier.width(8.dp))
        // Leaving this view is a separate choice from ending the block: the session (timer,
        // notification) keeps running in the background — "Hide" only steps back to the normal
        // timeline, "End Block" is the only action that actually stops the session.
        if (!isPlanningMode && onExitScope != null) {
            TextButton(
                onClick = onExitScope,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    "Hide",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        OutlinedButton(
            onClick = onEndSession,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, blockColor.copy(alpha = 0.55f))
        ) {
            Text(
                if (isPlanningMode) "Close" else "End Block",
                style = MaterialTheme.typography.labelSmall,
                color = blockColor
            )
        }
    }
}

@Composable
private fun BsHourLabels(viewStartMs: Long, totalHours: Int, onSV: Color, hourHeight: Dp) {
    Box(Modifier.width(BS_LABEL_WIDTH).fillMaxHeight()) {
        for (h in 0..totalHours) {
            val yOff = (hourHeight * h - 8.dp).coerceAtLeast(2.dp)
            val wallHour = Calendar.getInstance().apply {
                timeInMillis = viewStartMs + h * 3600_000L
            }.get(Calendar.HOUR_OF_DAY)
            Text(
                text = "%02d:00".format(wallHour),
                modifier = Modifier.bsYOffset(yOff),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = onSV.copy(alpha = 0.38f)
            )
            // Quarter-hour labels
            if (h < totalHours) {
                for (m in listOf(15, 30, 45)) {
                    val minYOff = (hourHeight * h + hourHeight * m / 60f - 6.dp).coerceAtLeast(2.dp)
                    Text(
                        text = ":%02d".format(m),
                        modifier = Modifier.bsYOffset(minYOff).padding(start = 4.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = onSV.copy(alpha = 0.25f)
                    )
                }
            }
        }
    }
}

@Composable
private fun BsTimelineBody(
    modifier: Modifier,
    viewStartMs: Long,
    totalHours: Int,
    totalMinutes: Int,
    hourHeight: Dp,
    scheduledStartMs: Long,
    scheduledEndMs: Long,
    blockSubTasks: List<ScheduledEvent>,
    nowMin: Int,
    isNowVisible: Boolean,
    blockColor: Color,
    taskTileBg: Color,
    taskTileFg: Color,
    outline: Color,
    onSV: Color,
    onTaskClick: ((ScheduledEvent) -> Unit)? = null,
    onColorPick: ((ScheduledEvent) -> Unit)? = null,
    onFreeSlotClick: ((startMin: Int, endMin: Int) -> Unit)? = null
) {
    val nowLineColor = MaterialTheme.colorScheme.error
    Box(modifier.fillMaxHeight().clipToBounds()) {

        // Grid lines
        Canvas(Modifier.fillMaxSize()) {
            for (h in 0..totalHours) {
                val y = h * hourHeight.toPx()
                drawLine(outline, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
                if (h < totalHours) {
                    for (q in 1..3) {
                        val qy = y + q * hourHeight.toPx() / 4f
                        drawLine(outline.copy(alpha = 0.55f), Offset(0f, qy), Offset(size.width, qy),
                            strokeWidth = 0.4.dp.toPx())
                    }
                }
            }
        }

        // Block window background fill
        val blockStartMin = bsMsToMin(scheduledStartMs, viewStartMs).coerceAtLeast(0)
        val blockEndMin   = bsMsToMin(scheduledEndMs,   viewStartMs).coerceAtMost(totalMinutes)
        if (blockEndMin > blockStartMin) {
            val bStartY = bsMinToY(blockStartMin, hourHeight)
            val fillH   = (bsMinToY(blockEndMin, hourHeight) - bStartY).coerceAtLeast(0.dp)
            Box(
                Modifier
                    .bsYOffset(bStartY)
                    .fillMaxWidth()
                    .height(fillH)
                    .background(blockColor.copy(alpha = 0.08f))
            )
            // Top and bottom boundary lines
            Canvas(Modifier.bsYOffset(bStartY).fillMaxWidth().height(fillH)) {
                drawLine(blockColor.copy(alpha = 0.45f), Offset(0f, 0f), Offset(size.width, 0f),
                    strokeWidth = 2.dp.toPx())
                drawLine(blockColor.copy(alpha = 0.45f), Offset(0f, size.height), Offset(size.width, size.height),
                    strokeWidth = 2.dp.toPx())
            }
        }

        // Free windows within the block
        val occupied = buildList {
            blockSubTasks.forEach { se ->
                val s = bsMsToMin(se.startMillis, viewStartMs)
                val e = bsMsToMin(se.endMillis, viewStartMs)
                if (e > s) add(s to e)
            }
        }.sortedBy { it.first }

        val merged = mutableListOf<Pair<Int, Int>>()
        for ((s, e) in occupied) {
            val last = merged.lastOrNull()
            if (last != null && s <= last.second) merged[merged.size - 1] = last.first to maxOf(last.second, e)
            else merged += s to e
        }

        var cursor = blockStartMin
        for ((occStart, occEnd) in merged) {
            if (occStart >= blockEndMin) break
            val gapEnd = occStart.coerceIn(blockStartMin, blockEndMin)
            val gapStart = cursor
            if (gapEnd > cursor && gapEnd - cursor >= 10)
                BsFreeWindow(gapStart, gapEnd, onSV, hourHeight, onClick = onFreeSlotClick?.let { cb -> { cb(gapStart, gapEnd) } })
            if (occEnd > cursor) cursor = occEnd
        }
        val finalCursor = cursor
        if (blockEndMin > finalCursor && blockEndMin - finalCursor >= 10)
            BsFreeWindow(finalCursor, blockEndMin, onSV, hourHeight, onClick = onFreeSlotClick?.let { cb -> { cb(finalCursor, blockEndMin) } })

        // Task tiles
        blockSubTasks.sortedBy { it.startMillis }.forEach { se ->
            val placementLabel = when {
                se.endMillis <= scheduledStartMs -> "before block"
                se.startMillis >= scheduledEndMs -> "after block"
                else -> null
            }
            BsTaskTile(se, viewStartMs, totalMinutes, hourHeight,
                defaultBg = taskTileBg,
                defaultFg = taskTileFg,
                placementLabel = placementLabel,
                onTaskClick = onTaskClick,
                onColorPick = onColorPick)
        }

        // Current-time indicator
        if (isNowVisible) {
            val clampedNow = nowMin.coerceIn(0, totalMinutes)
            val nowY = bsMinToY(clampedNow, hourHeight)
            Text(
                text = bsFmt(viewStartMs + clampedNow * 60_000L),
                color = nowLineColor,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                modifier = Modifier.bsYOffset(nowY - 20.dp).padding(start = 2.dp)
            )
            Canvas(Modifier.bsYOffset(nowY - 4.dp).fillMaxWidth().height(8.dp)) {
                val cy = size.height / 2f
                drawCircle(nowLineColor, 4.dp.toPx(), Offset(0f, cy))
                drawLine(nowLineColor, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1.5.dp.toPx())
            }
        }
    }
}

@Composable
private fun BsFreeWindow(startMin: Int, endMin: Int, onSV: Color, hourHeight: Dp, onClick: (() -> Unit)? = null) {
    val startY = bsMinToY(startMin, hourHeight)
    val blockH = (bsMinToY(endMin, hourHeight) - startY).coerceAtLeast(4.dp)
    val durMin = endMin - startMin
    val h = durMin / 60; val m = durMin % 60
    val durLabel = when {
        durMin >= 60 && m > 0 -> "${h}h ${m}m"
        durMin >= 60           -> "${h}h"
        else                   -> "${durMin}m"
    }
    Box(
        Modifier
            .bsYOffset(startY)
            .fillMaxWidth()
            .height(blockH)
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(onSV.copy(alpha = 0.03f))
            .border(1.dp, onSV.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
    ) {
        if (blockH >= 20.dp) {
            Text(
                text = "free · $durLabel",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = onSV.copy(alpha = 0.28f)
            )
        }
    }
}

@Composable
private fun BsTaskTile(
    se: ScheduledEvent,
    viewStartMs: Long,
    totalMinutes: Int,
    hourHeight: Dp,
    defaultBg: Color,
    defaultFg: Color,
    placementLabel: String? = null,
    onTaskClick: ((ScheduledEvent) -> Unit)? = null,
    onColorPick: ((ScheduledEvent) -> Unit)? = null
) {
    val seStartMin = bsMsToMin(se.startMillis, viewStartMs)
    val seEndMin   = bsMsToMin(se.endMillis,   viewStartMs)
    if (seStartMin >= totalMinutes || seEndMin <= 0) return

    val startY = bsMinToY(seStartMin, hourHeight)
    val eventH = (bsMinToY(seEndMin, hourHeight) - startY - 2.dp).coerceAtLeast(24.dp)

    // Per-task color overrides the default pair
    val tileColor = se.event.colorArgb?.toOpaqueColor()
    val bg = tileColor?.copy(alpha = 0.22f) ?: defaultBg
    val fg = tileColor ?: defaultFg

    Box(
        Modifier
            .bsYOffset(startY + 1.dp)
            .fillMaxWidth()
            .height(eventH)
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(if (onTaskClick != null) Modifier.clickable { onTaskClick(se) } else Modifier)
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (placementLabel != null) {
                Text(
                    placementLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = fg.copy(alpha = 0.55f),
                    letterSpacing = 0.5.sp
                )
            }
            Text(
                se.event.title,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                fontWeight = FontWeight.Medium,
                color = fg,
                maxLines = 1
            )
            if (eventH >= 36.dp) {
                Text(
                    "${bsFmt(se.startMillis)} – ${bsFmt(se.endMillis)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = fg.copy(alpha = 0.65f)
                )
            }
        }
        // Color dot in top-right corner — tap to change tile color
        if (onColorPick != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(fg.copy(alpha = 0.45f))
                    .clickable { onColorPick(se) }
            )
        }
    }
}

@Composable
private fun BsColorPickerDialog(
    currentArgb: Int?,
    onColorSelected: (Int) -> Unit,
    onClearColor: () -> Unit,
    onDismiss: () -> Unit
) {
    // A solid identity color, never meant to carry partial alpha — normalize any pre-existing
    // bad value so re-opening the picker for an old task repairs its color.
    var selectedColor by remember { mutableIntStateOf((currentArgb ?: BLOCK_COLORS.first()) or 0xFF000000.toInt()) }
    val isCustom = selectedColor !in BLOCK_COLORS

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Block color",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BLOCK_COLORS.forEach { argb ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .then(if (selectedColor == argb)
                                    Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                else Modifier)
                                .clickable { selectedColor = argb }
                        )
                    }
                    // Custom swatch
                    val customSwatchColor = if (isCustom) Color(selectedColor)
                                           else MaterialTheme.colorScheme.surfaceVariant
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(customSwatchColor)
                            .then(if (isCustom)
                                Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier)
                            .clickable { if (!isCustom) selectedColor = 0xFF808080.toInt() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (!isCustom) Icon(
                            Icons.Default.Add, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (isCustom) {
                    HsvColorPicker(
                        argb = selectedColor,
                        onColorChange = { selectedColor = it },
                        showAlpha = false
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { onClearColor(); onDismiss() }) { Text("Default") }
                    Row {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { onColorSelected(selectedColor); onDismiss() }) { Text("Apply") }
                    }
                }
            }
        }
    }
}
