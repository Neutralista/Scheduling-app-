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
    }

    fun getSchedule(blockId: String, date: LocalDate): NamedBlockSchedule? =
        schedules.getString("$blockId|${date.format(dateFmt)}", null)?.let {
            try { json.decodeFromString<NamedBlockSchedule>(it) } catch (_: Exception) { null }
        }

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
     *  filled in later by the planner) with their active tasks for [date]. */
    fun resolveFloatingInstancesForDate(date: LocalDate): List<NamedBlockInstance> =
        loadAllBlocks().filter { it.isFloating }.map { block ->
            NamedBlockInstance(block, 0L, 0L, resolveActiveTasks(block.id, date))
        }
}
