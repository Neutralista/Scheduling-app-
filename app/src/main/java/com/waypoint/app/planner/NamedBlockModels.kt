package com.waypoint.app.planner

import kotlinx.serialization.Serializable
import java.time.LocalDate

// TaskConditionSpec is defined in TaskQueueStore.kt (same package)

/** Placement of a task relative to its parent named block. */
@Serializable
enum class BlockTaskPlacement { BEFORE, DURING, AFTER }

/** Duration measurement for a single block-task execution within a session. */
@Serializable
data class BlockTaskMeasurement(
    val taskId: String,
    val startMs: Long,
    val endMs: Long,
    val measuredMinutes: Int = ((endMs - startMs) / 60_000L).coerceAtLeast(1).toInt()
)

/** Position preference within the block window, only meaningful when placement == DURING. */
@Serializable
enum class BlockSubPlacement { START, MID, END }

/**
 * Definition of a recurring named time block (e.g. "Gym", "Dance lessons").
 * The block has a recurring base schedule plus optional per-date overrides.
 */
@Serializable
data class NamedBlock(
    val id: String,
    val name: String,
    val colorArgb: Int? = null,
    val estimatedMinutes: Int = 60,
    val canStartEarly: Boolean = true,
    val canRunLate: Boolean = true,
    /** ISO day-of-week set for the recurring base: 1=Mon … 7=Sun. Empty = no recurring default. */
    val recurringDays: List<Int> = emptyList(),
    val defaultStartHour: Int = 9,
    val defaultStartMinute: Int = 0,
    val defaultEndHour: Int = -1,      // -1 = duration mode; ≥0 = time-range mode
    val defaultEndMinute: Int = 0,
    /** When true, the block is placed by the scheduler like a task (no fixed recurring schedule). */
    val isFloating: Boolean = false,
    /** Day/time conditions that govern when a floating block is eligible to be scheduled. */
    val floatingConditions: List<TaskConditionSpec> = emptyList(),
    /** Relative priority among floating blocks (higher = placed first). */
    val priority: Int = 5,
    /** When true, estimatedMinutes is recomputed from the sum of block task durations on each save. */
    val useTotalTaskDuration: Boolean = false,
    /** Flexible recurrence rule; when set takes precedence over [recurringDays]. */
    val recurrenceRule: RecurrenceRule? = null,
    /** When false, no start notification/alarm fires for this block. */
    val notificationsEnabled: Boolean = true,
    /** Soft gap reserved after this block when the scheduler places it (floating blocks only).
     *  0 = use the scheduler's automatic default gap, same as an unset task buffer. */
    val bufferMinutes: Int = 0,
    /** Soft time-of-day preference for floating blocks — MORNING is the scheduler's default
     *  first-fit behavior anyway; EVENING places the block as late as possible instead. Shown
     *  in the block's own Time of day section, alongside Around a time/Between two times. */
    val zone: PlannerZone? = null,
    /** For a block an external app (Training-app) owns: the days it last sent. Its days are only
     *  applied when they change from this, so a schedule edited in Waypoint isn't reset on every
     *  sync. Null for blocks created in Waypoint. */
    val externalDays: List<Int>? = null,
    /**
     * The block's phases, in the order they run (e.g. Gym → Warmup, Workout, Cooldown). One
     * level only: a phase holds tasks ([BlockTask.phaseId]), never phases. They split the
     * block's window back to back from its start; time left after the last is unphased.
     */
    val phases: List<BlockPhase> = emptyList(),
    /**
     * Off: the whole block is paused — not planned, no start alert, not offered to start — until
     * switched back on. Its tasks, schedule and history are kept.
     */
    val enabled: Boolean = true
)

/**
 * A stretch of a block with its own tasks. Its length is [durationMinutes], or when that's
 * null (or shorter) the total of its active tasks.
 */
@Serializable
data class BlockPhase(
    val id: String,
    val name: String,
    val durationMinutes: Int? = null,
    val colorArgb: Int? = null
)

/** Planner id for a phase's window: DuringBlock(phaseKey(...)) puts a task inside the phase. */
fun phaseKey(blockId: String, phaseId: String) = "$blockId#$phaseId"

/** A phase of one block occurrence, placed in time. */
data class PhaseWindow(val phase: BlockPhase, val startMs: Long, val endMs: Long)

/** How long [phase] runs given the block's active tasks: its set length or its tasks' total. */
fun phaseLengthMinutes(phase: BlockPhase, activeTasks: List<BlockTask>): Int {
    val taskTotal = activeTasks
        .filter { it.phaseId == phase.id && it.placement == BlockTaskPlacement.DURING }
        .sumOf { it.durationMinutes }
    return maxOf(phase.durationMinutes ?: 0, taskTotal)
}

/**
 * [block]'s phases laid back to back from [startMs], each cut off at [endMs]. A phase with no
 * length (no set duration, no active tasks) or no room left is left out.
 */
fun phaseWindows(block: NamedBlock, activeTasks: List<BlockTask>, startMs: Long, endMs: Long): List<PhaseWindow> {
    var cursor = startMs
    return block.phases.mapNotNull { phase ->
        val len = phaseLengthMinutes(phase, activeTasks) * 60_000L
        if (len <= 0 || cursor >= endMs) return@mapNotNull null
        val end = minOf(cursor + len, endMs)
        PhaseWindow(phase, cursor, end).also { cursor = end }
    }
}

/**
 * The phase a session of [block] is in at [nowMs]: the last one started with Next phase
 * ([phaseStarts]), or when none was, the one the plan has running now (phases back to back
 * from the session's start).
 */
fun currentPhase(
    block: NamedBlock,
    activeTasks: List<BlockTask>,
    startedAtMs: Long,
    endMs: Long,
    phaseStarts: Map<String, Long>,
    nowMs: Long
): BlockPhase? {
    val started = block.phases.filter { it.id in phaseStarts }
    if (started.isNotEmpty()) return started.maxByOrNull { phaseStarts.getValue(it.id) }
    return phaseWindows(block, activeTasks, startedAtMs, endMs)
        .firstOrNull { nowMs >= it.startMs && nowMs < it.endMs }?.phase
}

/**
 * How a finished session's phases went. With phases started by hand, each runs from its start
 * to the next one's (the last to [endedAtMs]); otherwise they're the planned windows from the
 * session's start, cut off at its end.
 */
fun sessionPhaseTimings(
    block: NamedBlock,
    activeTasks: List<BlockTask>,
    startedAtMs: Long,
    endedAtMs: Long,
    phaseStarts: Map<String, Long>
): List<PhaseTiming> {
    if (phaseStarts.isEmpty()) {
        return phaseWindows(block, activeTasks, startedAtMs, endedAtMs)
            .map { PhaseTiming(it.phase.id, it.phase.name, it.startMs, it.endMs) }
    }
    val started = block.phases.filter { it.id in phaseStarts }.sortedBy { phaseStarts.getValue(it.id) }
    return started.mapIndexed { i, phase ->
        val start = phaseStarts.getValue(phase.id)
        val end = started.getOrNull(i + 1)?.let { phaseStarts.getValue(it.id) } ?: endedAtMs
        PhaseTiming(phase.id, phase.name, start, maxOf(start, end))
    }
}

/** The phase [task] belongs to on [block], or null if it's unphased or its phase was deleted. */
fun BlockTask.phaseOf(block: NamedBlock): BlockPhase? =
    phaseId?.let { id -> block.phases.find { it.id == id } }?.takeIf { placement == BlockTaskPlacement.DURING }

/** One entry an external app (e.g. Training-app) sends when syncing a block's task list — see
 *  [NamedBlockStore.upsertExternalBlock]. [externalId] is that app's own stable id for the
 *  entry (e.g. its exercise id), not a Waypoint [BlockTask] id. */
data class ExternalBlockExercise(
    val externalId: String,
    val title: String,
    val durationMinutes: Int
)

/**
 * Per-date schedule entry for a named block.
 * Overrides the recurring default for a specific calendar date.
 */
@Serializable
data class NamedBlockSchedule(
    val blockId: String,
    val date: String,           // "yyyy-MM-dd"
    val enabled: Boolean = true,
    val startHour: Int = 9,
    val startMinute: Int = 0,
    val endHour: Int = -1,             // -1 = use block's estimatedMinutes; ≥0 = explicit end
    val endMinute: Int = 0
)

/**
 * A task that lives inside a named block.
 * [isAlways] = true → scheduled every occurrence automatically.
 * [isAlways] = false → situational; must be explicitly activated per occurrence.
 */
@Serializable
data class BlockTask(
    val id: String,
    val blockId: String,
    val title: String,
    val durationMinutes: Int,
    val placement: BlockTaskPlacement,
    val priority: Int = 5,
    val bufferMinutes: Int = 0,
    val isAlways: Boolean = true,
    val subPlacement: BlockSubPlacement? = null,
    /** When set, this task runs in a fixed order relative to other sequenced tasks that share
     *  its [placement] (BEFORE/DURING/AFTER chain independently). Null = flexible placement. */
    val sequence: Int? = null,
    val conditions: List<TaskConditionSpec> = emptyList(),
    val useMeasuredDuration: Boolean = false,
    val triggers: List<TaskTrigger> = emptyList(),
    val isRoutine: Boolean = false,
    val subtasks: List<SubtaskDef> = emptyList(),
    val colorArgb: Int? = null,
    /** The [BlockPhase] this DURING task runs in; null = anywhere in the block. */
    val phaseId: String? = null
)

/**
 * Resolves the "new, not yet numbered" sequence sentinel (-1, set by AddTaskSheet when it has no
 * visibility into sibling tasks) against the actual sibling list, assigning the next number in
 * that placement's chain. A no-op for flexible tasks (sequence == null) or already-numbered ones.
 */
fun BlockTask.withResolvedSequence(existingSiblings: List<BlockTask>): BlockTask {
    if (sequence != -1) return this
    val maxExisting = existingSiblings
        .filter { it.id != id && it.placement == placement && it.phaseId == phaseId && it.sequence != null }
        .maxOfOrNull { it.sequence!! } ?: -1
    return copy(sequence = maxExisting + 1)
}

/**
 * Finds the sequenced sibling adjacent to [task] within its placement's chain (same [BlockTask
 * .blockId] implied by [siblings]), one step earlier (direction = -1) or later (direction = +1).
 * Null past either end, or if [task] itself isn't sequenced.
 */
fun findAdjacentSequencedSibling(task: BlockTask, siblings: List<BlockTask>, direction: Int): BlockTask? {
    if (task.sequence == null) return null
    // Chains run per placement and, within DURING, per phase.
    val ordered = siblings.filter { it.placement == task.placement && it.phaseId == task.phaseId && it.sequence != null }
        .sortedBy { it.sequence!! }
    val idx = ordered.indexOfFirst { it.id == task.id }
    if (idx < 0) return null
    return ordered.getOrNull(idx + direction)
}

/**
 * Tracks which situational tasks are active for a specific block occurrence (date).
 * Only situational tasks need entries here; always-on tasks are implicitly active.
 */
@Serializable
data class BlockTaskActivation(
    val blockId: String,
    val date: String,           // "yyyy-MM-dd"
    val activeTaskIds: Set<String> = emptySet()
)

/**
 * The canonical effective-duration computation for a block occurrence, given the tasks
 * already resolved as active for it. When [NamedBlock.useTotalTaskDuration] is true, sums
 * only the DURING-placement tasks; falls back to [NamedBlock.estimatedMinutes] when that
 * sum is zero (e.g. no DURING tasks configured). Callers that only have a date (not a
 * pre-resolved task list) should go through [NamedBlockStore.effectiveDurationMinutes]
 * instead, which resolves the tasks and delegates here.
 */
fun effectiveDurationMinutes(block: NamedBlock, activeTasks: List<BlockTask>): Int {
    if (!block.useTotalTaskDuration) return block.estimatedMinutes
    // Unphased DURING tasks, plus each phase's length (a phase can be set longer than its tasks).
    val unphased = activeTasks
        .filter { it.placement == BlockTaskPlacement.DURING && it.phaseOf(block) == null }
        .sumOf { it.durationMinutes }
    val taskTotal = unphased + block.phases.sumOf { phaseLengthMinutes(it, activeTasks) }
    return taskTotal.takeIf { it > 0 } ?: block.estimatedMinutes
}

/**
 * Resolved named block instance for a specific date — ready for the planner.
 * [effectiveStartMs] and [effectiveEndMs] are updated post-scheduling to reflect
 * tasks that pulled the start earlier or pushed the end later.
 */
data class NamedBlockInstance(
    val block: NamedBlock,
    val scheduledStartMs: Long,
    val estimatedEndMs: Long,
    val activeTasks: List<BlockTask> = emptyList(),
    /**
     * Tasks already done in a session logged today, at the times they were actually done
     * (BlockTaskMeasurement, e.g. exercises Might logged). The planner places them there, as
     * past, instead of planning them again.
     */
    val taskActuals: Map<String, Pair<Long, Long>> = emptyMap()
)

private fun BlockSessionLog.actuals(): Map<String, Pair<Long, Long>> =
    taskMeasurements.associate { it.taskId to (it.startMs to it.endMs) }

/**
 * For today's date, overrides a fixed block instance's bounds with what actually happened once a
 * session exists for it — a live session's real start (and its current, possibly-extended
 * scheduled end), or a completed session's real start and end — instead of the merely-planned
 * schedule. Without this the planner keeps reserving the whole originally-scheduled window even
 * after a late start or an early finish, so time freed by the block never becomes available to
 * reschedule other floating events into. No-ops for any date other than today, since only today
 * can have a live or logged session to reconcile against.
 */
fun List<NamedBlockInstance>.reconciledWithActualSessions(
    date: LocalDate,
    activeSession: ActiveBlockSession?,
    logStore: BlockSessionLogStore?
): List<NamedBlockInstance> {
    if (date != LocalDate.now()) return this
    val dateKey = date.toString()
    val loggedToday = logStore?.loadAll()?.filter { it.date == dateKey }.orEmpty()
    return map { inst ->
        when {
            activeSession != null && activeSession.blockId == inst.block.id ->
                inst.copy(scheduledStartMs = activeSession.startedAtMs, estimatedEndMs = activeSession.scheduledEndMs)
            // The latest session — an earlier one (stopped, then restarted) is in the past and
            // can't affect what's still plannable; the first one used to win.
            else -> loggedToday.filter { it.blockId == inst.block.id }.maxByOrNull { it.startedAtMs }
                ?.let { inst.copy(scheduledStartMs = it.startedAtMs, estimatedEndMs = it.endedAtMs, taskActuals = it.actuals()) }
                ?: inst
        }
    }
}

/**
 * Splits today's floating blocks into those a session has already pinned down (running now, or
 * logged earlier today) and those the planner still has to place. Returns (pinned, floating);
 * each pinned block becomes a fixed instance at its real start and end. Left floating, a running
 * block was placed a second time elsewhere with its tasks lined up around that copy, and a
 * finished one came back later the same day.
 */
fun List<NamedBlockInstance>.pinnedBySessions(
    date: LocalDate,
    activeSession: ActiveBlockSession?,
    logStore: BlockSessionLogStore?
): Pair<List<NamedBlockInstance>, List<NamedBlockInstance>> {
    if (date != LocalDate.now()) return emptyList<NamedBlockInstance>() to this
    val dateKey = date.toString()
    val loggedToday = logStore?.loadAll()?.filter { it.date == dateKey }.orEmpty()
    val pinned = mutableListOf<NamedBlockInstance>()
    val floating = mutableListOf<NamedBlockInstance>()
    for (inst in this) {
        val live = activeSession?.takeIf { it.blockId == inst.block.id && it.date == dateKey }
        val logged = loggedToday.filter { it.blockId == inst.block.id }.maxByOrNull { it.startedAtMs }
        when {
            live != null -> pinned += inst.copy(scheduledStartMs = live.startedAtMs, estimatedEndMs = live.scheduledEndMs)
            logged != null -> pinned += inst.copy(
                scheduledStartMs = logged.startedAtMs, estimatedEndMs = logged.endedAtMs, taskActuals = logged.actuals()
            )
            else -> floating += inst
        }
    }
    return pinned to floating
}
