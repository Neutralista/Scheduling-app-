package com.waypoint.app.planner

import kotlinx.serialization.Serializable

/** Placement of a task relative to its parent named block. */
@Serializable
enum class BlockTaskPlacement { BEFORE, DURING, AFTER }

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
    val defaultStartMinute: Int = 0
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
    val startMinute: Int = 0
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
    val isAlways: Boolean = true
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
