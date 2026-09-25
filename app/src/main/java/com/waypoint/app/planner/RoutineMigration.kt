package com.waypoint.app.planner

import com.waypoint.app.AppLogger
import com.waypoint.app.script.TaskManagerScript

/**
 * Routines (a floating task with isRoutine + subtasks) were a second, weaker way to do what an
 * auto-placed block does. Each one becomes an auto-placed block with the same id, name, colour,
 * priority, buffer, time of day and conditions, whose steps are its always-on DURING tasks in
 * order; the block's length is their total. References to the routine from other tasks and
 * blocks move to the block. Its chain triggers are dropped: blocks have none. A routine with no
 * steps just stops being one.
 */
object RoutineMigration {

    private const val TAG = "RoutineMigration"

    data class Converted(val block: NamedBlock, val tasks: List<BlockTask>)

    fun isConvertible(req: TaskRequest): Boolean = req.isRoutine && req.subtasks.isNotEmpty()

    fun toBlock(req: TaskRequest): Converted {
        val tasks = req.subtasks.mapIndexed { index, sub ->
            BlockTask(
                id = "${req.id}-${sub.id}",
                blockId = req.id,
                title = sub.title,
                durationMinutes = sub.defaultDurationMinutes.coerceAtLeast(1),
                placement = BlockTaskPlacement.DURING,
                isAlways = true,
                sequence = index
            )
        }
        val block = NamedBlock(
            id = req.id,
            name = req.title,
            colorArgb = req.colorArgb,
            estimatedMinutes = tasks.sumOf { it.durationMinutes }.takeIf { it > 0 } ?: req.durationMinutes,
            isFloating = true,
            floatingConditions = req.conditions,
            priority = req.priority,
            useTotalTaskDuration = true,
            bufferMinutes = req.bufferMinutes,
            // scheduleLate was how a task said "evening"; a block says it with its zone.
            zone = req.zone ?: if (req.scheduleLate) PlannerZone.EVENING else null
        )
        return Converted(block, tasks)
    }

    /**
     * [conditions] with references to the converted routines ([routineIds], now block ids)
     * moved over: before/after one becomes before/after its block (unless one's already set,
     * as a task or block holds one of each), same-day references are dropped, and a condition
     * left with no references goes. Returns null when nothing referred to them.
     */
    fun rewriteConditions(conditions: List<TaskConditionSpec>, routineIds: Set<String>): List<TaskConditionSpec>? {
        if (conditions.none { c -> c.referenceTaskIds.orEmpty().any { it in routineIds } }) return null
        val out = mutableListOf<TaskConditionSpec>()
        val blockConds = mutableListOf<TaskConditionSpec>()
        for (c in conditions) {
            val refs = c.referenceTaskIds.orEmpty()
            val hit = refs.filter { it in routineIds }
            if (hit.isEmpty()) { out += c; continue }
            val rest = refs - hit.toSet()
            if (rest.isNotEmpty()) out += c.copy(referenceTaskIds = rest)
            when (c.type) {
                "afterTask" -> blockConds += TaskConditionSpec("afterBlock", blockId = hit.first())
                "beforeTask" -> blockConds += TaskConditionSpec("beforeBlock", blockId = hit.first())
            }
        }
        blockConds.forEach { bc -> if (out.none { it.type == bc.type }) out += bc }
        return out
    }

    /** Converts every routine in the task queue. Safe to run every launch: a no-op once done. */
    fun run(taskManager: TaskManagerScript, blocks: NamedBlockStore) {
        val all = taskManager.getAllTasks()
        val routines = all.filter { it.isRoutine }
        if (routines.isEmpty()) return
        val converting = routines.filter(::isConvertible)
        val ids = converting.map { it.id }.toSet()

        converting.forEach { req ->
            val (block, tasks) = toBlock(req)
            tasks.forEach { blocks.saveTask(it) }
            blocks.saveBlock(block)
            AppLogger.i(TAG, "run: routine '${req.title}' → auto-placed block with ${tasks.size} step(s)")
        }
        // Stepless routines stay tasks, just not routines.
        routines.filterNot(::isConvertible).forEach {
            taskManager.submitTask(it.copy(isRoutine = false, subtasks = emptyList()))
        }
        if (ids.isEmpty()) return

        all.filter { it.id !in ids }.forEach { req ->
            val conds = rewriteConditions(req.conditions, ids)
            val triggers = req.triggers.filterNot { it.chainTaskId in ids }
            if (conds != null || triggers.size != req.triggers.size) {
                taskManager.submitTask(req.copy(conditions = conds ?: req.conditions, triggers = triggers))
            }
        }
        blocks.loadAllBlocks().filter { it.id !in ids }.forEach { b ->
            rewriteConditions(b.floatingConditions, ids)?.let { blocks.saveBlock(b.copy(floatingConditions = it)) }
        }
        ids.forEach { taskManager.retractTask(it) }
    }
}
