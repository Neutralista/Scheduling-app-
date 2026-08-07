package com.waypoint.app.planner

import kotlinx.serialization.Serializable

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
    val notificationsEnabled: Boolean = true
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
    val conditions: List<TaskConditionSpec> = emptyList(),
    val useMeasuredDuration: Boolean = false,
    val triggers: List<TaskTrigger> = emptyList(),
    val isRoutine: Boolean = false,
    val subtasks: List<SubtaskDef> = emptyList(),
    val colorArgb: Int? = null
)

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
 * Resolved named block instance for a specific date — ready for the planner.
 * [effectiveStartMs] and [effectiveEndMs] are updated post-scheduling to reflect
 * tasks that pulled the start earlier or pushed the end later.
 */
data class NamedBlockInstance(
    val block: NamedBlock,
    val scheduledStartMs: Long,
    val estimatedEndMs: Long,
    val activeTasks: List<BlockTask> = emptyList()
)
