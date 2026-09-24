package com.waypoint.app.planner

import android.content.Context
import com.waypoint.app.alarm.AlarmBlockSync
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class NamedBlockStore(private val context: Context) {

    private val blocks   = context.getSharedPreferences("wp_named_blocks",       Context.MODE_PRIVATE)
    private val schedules = context.getSharedPreferences("wp_block_schedules",    Context.MODE_PRIVATE)
    private val tasks    = context.getSharedPreferences("wp_block_tasks",         Context.MODE_PRIVATE)
    private val activations = context.getSharedPreferences("wp_block_activations", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    // ── Block definitions ─────────────────────────────────────────────────────

    fun saveBlock(block: NamedBlock) {
        blocks.edit().putString(block.id, json.encodeToString(block)).apply()
        AlarmBlockSync.sync(context)
        BlockAlarmScheduler.resync(context, block.id)
    }

    fun deleteBlock(blockId: String) {
        blocks.edit().remove(blockId).apply()
        // Cascade: remove schedules, tasks, and activations for this block
        schedules.edit().apply {
            schedules.all.keys.filter { it.startsWith("$blockId|") }.forEach { remove(it) }
        }.apply()
        val taskIds = loadTasksForBlock(blockId).map { it.id }
        tasks.edit().apply { taskIds.forEach { remove(it) } }.apply()
        activations.edit().apply {
            activations.all.keys.filter { it.startsWith("$blockId|") }.forEach { remove(it) }
        }.apply()
        AlarmBlockSync.sync(context)
        BlockAlarmScheduler.resync(context, blockId)
    }

    /**
     * Creates or updates a block owned by an external app (e.g. Training-app) along with one
     * always-on DURING task per entry in [exercises] — the block's own duration then tracks their
     * sum via useTotalTaskDuration, so a re-sync that only changes durations (an updated average)
     * reshapes the block automatically. [exercises] is (externalId, title, durationMinutes,
     * orderIndex); task ids are derived as "$blockId-$externalId" so re-syncing updates existing
     * tasks in place rather than duplicating them, and any task whose externalId is no longer
     * present (e.g. an exercise removed from the source workout) is deleted. An empty [exercises]
     * list deletes the whole block instead of leaving an empty one behind.
     *
     * [recurringDays] (ISO weekday numbers, 1=Mon..7=Sun) makes this a normal fixed/recurring
     * block on those days, same as one created in-app; empty makes it floating — "any day, planner
     * decides".
     *
     * The external app owns the exercise list and the days; everything else is Waypoint's. So a
     * re-sync only updates each task's title, duration and order, and applies the days only when
     * they differ from what was last sent ([NamedBlock.externalDays]). Rewriting whole tasks and
     * the schedule on every sync wiped any priority, colour, condition or schedule set here.
     * A new block starts with notifications off: no start time comes with the sync, so it would
     * otherwise notify at the 09:00 default until one is set.
     */
    fun upsertExternalBlock(
        blockId: String,
        name: String,
        exercises: List<ExternalBlockExercise>,
        recurringDays: List<Int> = emptyList()
    ) {
        if (exercises.isEmpty()) {
            deleteBlock(blockId)
            return
        }
        val days = recurringDays.distinct().sorted()
        val base = loadBlock(blockId)
            ?: NamedBlock(id = blockId, name = name, useTotalTaskDuration = true, notificationsEnabled = false)
        val scheduled = if (base.externalDays == days) base else base.copy(
            isFloating = days.isEmpty(),
            recurringDays = days,
            recurrenceRule = null,
            externalDays = days
        )
        saveBlock(scheduled.copy(name = name))

        val previous = loadTasksForBlock(blockId).associateBy { it.id }
        val currentTaskIds = mutableSetOf<String>()
        exercises.forEachIndexed { index, ex ->
            val taskId = "$blockId-${ex.externalId}"
            currentTaskIds += taskId
            val minutes = ex.durationMinutes.coerceAtLeast(1)
            saveTask(
                previous[taskId]?.copy(title = ex.title, durationMinutes = minutes, sequence = index)
                    ?: BlockTask(
                        id = taskId,
                        blockId = blockId,
                        title = ex.title,
                        durationMinutes = minutes,
                        placement = BlockTaskPlacement.DURING,
                        isAlways = true,
                        sequence = index
                    )
            )
        }
        (previous.keys - currentTaskIds).forEach { deleteTask(it) }
    }

    fun loadAllBlocks(): List<NamedBlock> = blocks.all.values.mapNotNull { raw ->
        try { json.decodeFromString<NamedBlock>(raw as? String ?: return@mapNotNull null) }
        catch (_: Exception) { null }
    }

    fun loadBlock(blockId: String): NamedBlock? =
        blocks.getString(blockId, null)?.let {
            try { json.decodeFromString<NamedBlock>(it) } catch (_: Exception) { null }
        }

    // ── Per-date schedules ────────────────────────────────────────────────────

    fun setSchedule(schedule: NamedBlockSchedule) {
        schedules.edit().putString("${schedule.blockId}|${schedule.date}", json.encodeToString(schedule)).apply()
        AlarmBlockSync.sync(context)
        BlockAlarmScheduler.resync(context, schedule.blockId)
    }

    fun getSchedule(blockId: String, date: LocalDate): NamedBlockSchedule? =
        schedules.getString("$blockId|${date.format(dateFmt)}", null)?.let {
            try { json.decodeFromString<NamedBlockSchedule>(it) } catch (_: Exception) { null }
        }

    /**
     * Skip a single occurrence of [blockId] on [date] entirely — same effect as disabling
     * that day via the 14-day schedule editor, just reachable from wherever the block is
     * about to start (the "starts now" notification, the Plan-tab timeline). Works for both
     * fixed and floating blocks; setSchedule's resync also cancels any already-armed start
     * alarm for the day, and resolveForDate/resolveFloatingInstancesForDate both honor it so
     * the block stops reserving time on the timeline too.
     */
    fun skipForDate(blockId: String, date: LocalDate) {
        val existing = getSchedule(blockId, date)
        setSchedule((existing ?: NamedBlockSchedule(blockId = blockId, date = date.format(dateFmt))).copy(enabled = false))
    }

    fun isSkippedForDate(blockId: String, date: LocalDate): Boolean =
        getSchedule(blockId, date)?.enabled == false

    /** Returns all block instances that are active on [date], with their resolved start times. */
    fun resolveForDate(date: LocalDate): List<Pair<NamedBlock, NamedBlockSchedule>> {
        val dateStr = date.format(dateFmt)
        val dayOfWeek = date.dayOfWeek.value  // 1=Mon..7=Sun
        return loadAllBlocks().mapNotNull { block ->
            if (block.isFloating) return@mapNotNull null  // floating blocks are placed by the planner
            // Per-date override takes precedence
            val override = schedules.getString("${block.id}|$dateStr", null)?.let {
                try { json.decodeFromString<NamedBlockSchedule>(it) } catch (_: Exception) { null }
            }
            when {
                override != null -> if (override.enabled) block to override else null
                (block.recurrenceRule?.occursOn(date) ?: (dayOfWeek in block.recurringDays)) -> block to NamedBlockSchedule(
                    blockId = block.id, date = dateStr,
                    startHour = block.defaultStartHour, startMinute = block.defaultStartMinute,
                    endHour = block.defaultEndHour, endMinute = block.defaultEndMinute
                )
                else -> null
            }
        }
    }

    /**
     * Best-effort "could this floating block occur on [date]" check for UI purposes (e.g.
     * finding a floating block's next occurrence to plan situational tasks for). Floating
     * blocks have no fixed day-of-week schedule by design — the planner alone decides whether
     * one is actually placed each day, based on free time and priority — so this only evaluates
     * the day-scoped condition that doesn't need live work-schedule context; workDayOnly/
     * dayOffOnly are treated as no constraint here since this store has no shift data. The
     * planner's own eligibility check in EventPlannerRegistry remains authoritative for
     * whether a floating block is actually scheduled on a given day.
     */
    fun isFloatingBlockPossibleOn(block: NamedBlock, date: LocalDate): Boolean {
        val dayOfWeek = date.dayOfWeek.value
        return block.floatingConditions.none { spec ->
            spec.type == "daysOfWeek" && spec.days?.let { dayOfWeek !in it } == true
        }
    }

    // ── Block tasks ───────────────────────────────────────────────────────────

    fun saveTask(task: BlockTask) =
        tasks.edit().putString(task.id, json.encodeToString(task)).apply()

    fun deleteTask(taskId: String) =
        tasks.edit().remove(taskId).apply()

    fun loadTask(taskId: String): BlockTask? =
        tasks.getString(taskId, null)?.let {
            try { json.decodeFromString<BlockTask>(it) } catch (_: Exception) { null }
        }

    fun loadTasksForBlock(blockId: String): List<BlockTask> =
        tasks.all.values.mapNotNull { raw ->
            try {
                val t = json.decodeFromString<BlockTask>(raw as? String ?: return@mapNotNull null)
                if (t.blockId == blockId) t else null
            } catch (_: Exception) { null }
        }

    fun updateTaskColor(taskId: String, colorArgb: Int?) {
        val task = loadTask(taskId) ?: return
        saveTask(task.copy(colorArgb = colorArgb))
    }

    // ── Situational task activations ──────────────────────────────────────────

    fun setActivation(activation: BlockTaskActivation) =
        activations.edit()
            .putString("${activation.blockId}|${activation.date}", json.encodeToString(activation))
            .apply()

    fun getActivation(blockId: String, date: LocalDate): BlockTaskActivation =
        activations.getString("$blockId|${date.format(dateFmt)}", null)?.let {
            try { json.decodeFromString<BlockTaskActivation>(it) } catch (_: Exception) { null }
        } ?: BlockTaskActivation(blockId = blockId, date = date.format(dateFmt))

    fun toggleSituational(blockId: String, date: LocalDate, taskId: String): BlockTaskActivation {
        val current = getActivation(blockId, date)
        val updated = current.copy(
            activeTaskIds = if (taskId in current.activeTaskIds)
                current.activeTaskIds - taskId else current.activeTaskIds + taskId
        )
        setActivation(updated)
        return updated
    }

    /**
     * Resolves which tasks are active for a block on a given date.
     * Always-on tasks are always included; situational tasks only if activated.
     * Day-of-week conditions on the task are enforced here so the UI and the
     * planner both see only tasks that are eligible on [date].
     */
    fun resolveActiveTasks(blockId: String, date: LocalDate): List<BlockTask> {
        val all = loadTasksForBlock(blockId)
        val activation = getActivation(blockId, date)
        val dayOfWeek = date.dayOfWeek.value  // 1=Mon..7=Sun
        return all.filter { task ->
            (task.isAlways || task.id in activation.activeTaskIds) &&
            task.conditions.none { spec ->
                spec.type == "daysOfWeek" && spec.days?.let { dayOfWeek !in it } == true
            }
        }
    }

    /**
     * Returns the effective duration in minutes for [block] on [date].
     * When [NamedBlock.useTotalTaskDuration] is true, sums only the tasks
     * active on that date (respecting day-of-week and situational conditions).
     * Falls back to [NamedBlock.estimatedMinutes] when the active sum is zero.
     */
    fun effectiveDurationMinutes(block: NamedBlock, date: LocalDate): Int =
        effectiveDurationMinutes(block, resolveActiveTasks(block.id, date))

    /**
     * How long [block] is planned to run on [date]: its scheduled window for a fixed block
     * (explicit end time or duration), otherwise its effective duration. A session keeps this
     * length from the moment it starts; ending at the planned end time instead turned an early
     * start into a session that reserved the rest of the day.
     */
    fun plannedDurationMs(block: NamedBlock, date: LocalDate): Long =
        resolveFixedInstancesForDate(date).find { it.block.id == block.id }
            ?.let { it.estimatedEndMs - it.scheduledStartMs }
            ?: (effectiveDurationMinutes(block, date) * 60_000L)

    // ── Planner instance resolution ─────────────────────────────────────────────

    /**
     * Resolves [date]'s fixed (non-floating) blocks into the [NamedBlockInstance] shape
     * EventPlannerRegistry.planForDate needs for its namedBlockInstances parameter, with
     * each block's active tasks and effective end time computed. Callers that display a
     * live in-session task list with measured-duration overrides (e.g. the timeline and
     * block-scope views) should keep resolving activeTasks themselves instead, since this
     * helper doesn't know about per-session measured-duration adjustments.
     */
    fun resolveFixedInstancesForDate(date: LocalDate): List<NamedBlockInstance> {
        val zone = ZoneId.systemDefault()
        return resolveForDate(date).map { (block, sched) ->
            val startMs = date.atTime(sched.startHour, sched.startMinute).atZone(zone).toInstant().toEpochMilli()
            val activeTasks = resolveActiveTasks(block.id, date)
            val endMs = if (sched.endHour >= 0) {
                val e = date.atTime(sched.endHour, sched.endMinute).atZone(zone).toInstant().toEpochMilli()
                if (e > startMs) e else e + 24 * 3600_000L
            } else startMs + effectiveDurationMinutes(block, activeTasks) * 60_000L
            NamedBlockInstance(block, startMs, endMs, activeTasks)
        }
    }

    /** Resolves floating blocks into unscheduled [NamedBlockInstance] placeholders (start/end
     *  filled in later by the planner) with their active tasks for [date]. Excludes blocks
     *  skipped for this date (see [skipForDate]) — floating blocks have no schedule record to
     *  disable, so this is the only place their skip actually takes effect. */
    fun resolveFloatingInstancesForDate(date: LocalDate): List<NamedBlockInstance> =
        loadAllBlocks().filter { it.isFloating && !isSkippedForDate(it.id, date) }.map { block ->
            NamedBlockInstance(block, 0L, 0L, resolveActiveTasks(block.id, date))
        }
}
