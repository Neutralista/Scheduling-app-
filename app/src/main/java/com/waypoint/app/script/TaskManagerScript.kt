package com.waypoint.app.script

import com.waypoint.app.AppLogger
import com.waypoint.app.persistence.TaskCompletionStore
import com.waypoint.app.persistence.TaskDoneHistoryStore
import com.waypoint.app.planner.EventCondition
import com.waypoint.app.planner.EventPlannerRegistry
import com.waypoint.app.planner.PlannerEvent
import com.waypoint.app.planner.TaskExecution
import com.waypoint.app.planner.TaskExecutionStore
import com.waypoint.app.planner.TaskQueueStore
import com.waypoint.app.planner.TaskRequest
import com.waypoint.app.planner.oneOffDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

class TaskManagerScript(
    private val store: TaskQueueStore,
    private val registry: EventPlannerRegistry,
    val completions: TaskCompletionStore,
    val executions: TaskExecutionStore,
    private val doneHistory: TaskDoneHistoryStore
) : AppScript {

    override val id = "built_in.task_manager"
    override val displayName = "Task Manager"

    companion object {
        private const val TAG = "TaskManagerScript"
        const val WIDGET_ID = "task_manager"
        /** How close a run's end and the done mark must be for the run to be what was marked done. */
        private const val RUN_MATCH_MS = 5 * 60_000L
    }

    override fun onAttached(env: ScriptEnvironment) {
        syncToRegistry()
    }

    fun submitTask(req: TaskRequest) {
        store.submit(req)
        AppLogger.i(TAG, "submitTask: id=${req.id}")
        syncToRegistry()
    }

    fun retractTask(taskId: String) {
        store.retract(taskId)
        doneHistory.forget(taskId)
        AppLogger.i(TAG, "retractTask: id=$taskId")
        syncToRegistry()
    }

    /**
     * A block was deleted: tasks set before, during or after it lose that condition and float
     * like any other, rather than sitting in Unscheduled for good waiting on a block that's gone.
     */
    fun dropBlockConditions(blockId: String) {
        val blockTypes = setOf("beforeBlock", "duringBlock", "afterBlock")
        store.loadAll()
            .filter { req -> req.conditions.any { it.type in blockTypes && it.blockId == blockId } }
            .forEach { req ->
                store.submit(req.copy(conditions = req.conditions.filterNot { it.type in blockTypes && it.blockId == blockId }))
            }
        syncToRegistry()
    }

    fun retractBySource(sourceScriptId: String) {
        store.retractBySource(sourceScriptId)
        syncToRegistry()
    }

    fun getAllTasks(): List<TaskRequest> = store.loadAll()

    /** Task id to the "yyyy-MM-dd" dates it was done on (kept a year). */
    fun doneHistory(): Map<String, Set<String>> = doneHistory.all()

    // Re-synced so a done task is pinned where it happened instead of still floating after now.
    fun markDone(taskId: String) = markDoneAt(taskId, System.currentTimeMillis())
    fun markDoneAt(taskId: String, whenMs: Long) {
        // Re-marked at a different time: the old day no longer counts as done.
        completions.getDoneAt(taskId)?.let { doneHistory.remove(taskId, dateOf(it)) }
        completions.markDoneAt(taskId, whenMs)
        doneHistory.add(taskId, dateOf(whenMs))
        setOneOffCompletedOn(taskId, dateOf(whenMs))
        syncToRegistry()
    }
    fun unmarkDone(taskId: String) {
        completions.getDoneAt(taskId)?.let { doneHistory.remove(taskId, dateOf(it)) }
        completions.unmarkDone(taskId)
        setOneOffCompletedOn(taskId, null)
        syncToRegistry()
    }
    fun isDone(taskId: String) = completions.isDone(taskId)

    fun skipTask(taskId: String) { completions.skipTask(taskId); syncToRegistry() }
    fun unskipTask(taskId: String) { completions.unskipTask(taskId); syncToRegistry() }
    fun isSkipped(taskId: String) = completions.isSkipped(taskId)

    fun startExecution(taskId: String): TaskExecution = executions.start(taskId)
    fun stopExecution(taskId: String): TaskExecution? = executions.stop(taskId)
    fun getRunningExecution(): TaskExecution? = executions.getRunning()
    fun getExecution(taskId: String): TaskExecution? = executions.get(taskId)

    /** Logs a completed occurrence of a measured-duration task without running its timer live —
     *  for something that actually happened earlier and just wasn't logged at the time. */
    fun logPastExecution(taskId: String, startMs: Long, endMs: Long) {
        executions.update(TaskExecution(taskId = taskId, startMillis = startMs, endMillis = endMs))
        markDoneAt(taskId, endMs)
    }

    private fun dateOf(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun setOneOffCompletedOn(taskId: String, date: String?) {
        val req = store.loadAll().find { it.id == taskId } ?: return
        if (req.conditions.oneOffDate() == null || req.completedOn == date) return
        store.submit(req.copy(completedOn = date))
    }

    /**
     * One-off tasks after their date. Done (by a past day, and no longer marked done this wake):
     * removed from the queue — before, they stayed in it forever. Not done: carried over to
     * today rather than silently dropped. Others pass through unchanged.
     */
    private fun settleOneOffs(tasks: List<TaskRequest>): List<TaskRequest> {
        val today = LocalDate.now()
        return tasks.mapNotNull { req ->
            val due = req.conditions.oneOffDate()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (due == null || !due.isBefore(today)) return@mapNotNull req
            val completed = req.completedOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (completed != null && completed.isBefore(today) && !completions.isDone(req.id)) {
                store.retract(req.id)
                AppLogger.i(TAG, "settleOneOffs: cleared done one-off id=${req.id}")
                return@mapNotNull null
            }
            req.copy(conditions = req.conditions.map {
                if (it.type == "oneOff") it.copy(oneOffDate = today.toString()) else it
            })
        }
    }

    fun syncToRegistry() {
        registry.unregisterByWidget(WIDGET_ID)
        val tasks = settleOneOffs(store.loadAll())
        // Loaded once and filtered per-task in memory, rather than re-reading and
        // re-decoding the entire executions store for every useMeasuredDuration task.
        val allExecutions = executions.loadAll()
        tasks.filter { !completions.isSkipped(it.id) }.forEach { req ->
            val effectiveDuration = if (req.useMeasuredDuration) {
                allExecutions
                    .filter { it.taskId == req.id && it.measuredMinutes != null }
                    .mapNotNull { it.measuredMinutes }
                    .average()
                    .takeIf { !it.isNaN() }
                    ?.toInt() ?: req.durationMinutes
            } else req.durationMinutes

            // Pin a running task at its start so the now-line progresses through it, and a done
            // one where it happened so it stops floating after now and holding time. That's its
            // timed run when the done mark came from stopping it, else the duration up to when
            // it was marked done. A finished run that's no longer marked done (unticked, or from
            // before this wake) doesn't pin: the task is to do again.
            val exec = executions.get(req.id)
            val doneAt = if (completions.isDone(req.id)) completions.getDoneAt(req.id) else null
            val finishedRun = exec?.takeIf { e ->
                val end = e.endMillis
                !e.isRunning && end != null && doneAt != null && abs(doneAt - end) <= RUN_MATCH_MS
            }
            val (fixedStart, fixedEnd) = when {
                exec != null && exec.isRunning -> exec.startMillis to exec.startMillis + effectiveDuration * 60_000L
                finishedRun != null     -> finishedRun.startMillis to finishedRun.endMillis
                doneAt != null          -> doneAt - effectiveDuration * 60_000L to doneAt
                else                    -> null to null
            }

            registry.register(
                PlannerEvent(
                    id = req.id,
                    title = req.title,
                    durationMinutes = effectiveDuration,
                    priority = req.priority,
                    conditions = req.conditions.mapNotNull { spec ->
                        when (val cond = spec.toEventCondition()) {
                            // A quota needs what's been done this period.
                            is EventCondition.NTimesPerPeriod -> cond.copy(doneDates = doneHistory.dates(req.id))
                            else -> cond
                        }
                    },
                    sourceWidgetId = WIDGET_ID,
                    bufferMinutes = req.bufferMinutes,
                    scheduleLate = req.scheduleLate,
                    zone = req.zone,
                    fixedStartMillis = fixedStart,
                    fixedEndMillis = fixedEnd,
                    // Pinned only on the day it ran; a recurring task still floats on other days.
                    pinnedDayOnly = true,
                    colorArgb = req.colorArgb
                )
            )
        }
        val skippedCount = tasks.count { completions.isSkipped(it.id) }
        AppLogger.i(TAG, "syncToRegistry: registered ${tasks.size - skippedCount} tasks (${skippedCount} skipped)")
    }
}
