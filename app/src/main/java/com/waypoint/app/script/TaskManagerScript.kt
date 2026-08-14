package com.waypoint.app.script

import com.waypoint.app.AppLogger
import com.waypoint.app.persistence.TaskCompletionStore
import com.waypoint.app.planner.EventPlannerRegistry
import com.waypoint.app.planner.PlannerEvent
import com.waypoint.app.planner.TaskExecution
import com.waypoint.app.planner.TaskExecutionStore
import com.waypoint.app.planner.TaskQueueStore
import com.waypoint.app.planner.TaskRequest

class TaskManagerScript(
    private val store: TaskQueueStore,
    private val registry: EventPlannerRegistry,
    val completions: TaskCompletionStore,
    val executions: TaskExecutionStore
) : AppScript {

    override val id = "built_in.task_manager"
    override val displayName = "Task Manager"

    companion object {
        private const val TAG = "TaskManagerScript"
        const val WIDGET_ID = "task_manager"
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
        AppLogger.i(TAG, "retractTask: id=$taskId")
        syncToRegistry()
    }

    fun retractBySource(sourceScriptId: String) {
        store.retractBySource(sourceScriptId)
        syncToRegistry()
    }

    fun getAllTasks(): List<TaskRequest> = store.loadAll()

    fun markDone(taskId: String) = completions.markDone(taskId)
    fun unmarkDone(taskId: String) = completions.unmarkDone(taskId)
    fun isDone(taskId: String) = completions.isDone(taskId)

    fun skipTask(taskId: String) { completions.skipTask(taskId); syncToRegistry() }
    fun unskipTask(taskId: String) { completions.unskipTask(taskId); syncToRegistry() }
    fun isSkipped(taskId: String) = completions.isSkipped(taskId)

    fun startExecution(taskId: String): TaskExecution = executions.start(taskId)
    fun stopExecution(taskId: String): TaskExecution? = executions.stop(taskId)
    fun getRunningExecution(): TaskExecution? = executions.getRunning()
    fun getExecution(taskId: String): TaskExecution? = executions.get(taskId)

    fun syncToRegistry() {
        registry.unregisterByWidget(WIDGET_ID)
        val tasks = store.loadAll()
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

            // Pin running tasks at their start so the now-line progresses through them.
            // Pin completed tasks at their actual logged start/end so they don't float.
            val runningExec   = executions.get(req.id)?.takeIf { it.isRunning }
            val completedExec = executions.get(req.id)?.takeIf { !it.isRunning && it.endMillis != null }
            val fixedStart = runningExec?.startMillis ?: completedExec?.startMillis
            val fixedEnd   = when {
                runningExec   != null -> fixedStart!! + effectiveDuration * 60_000L
                completedExec != null -> completedExec.endMillis
                else                  -> null
            }

            registry.register(
                PlannerEvent(
                    id = req.id,
                    title = req.title,
                    durationMinutes = effectiveDuration,
                    priority = req.priority,
                    conditions = if (fixedStart != null) emptyList()
                                 else req.conditions.mapNotNull { it.toEventCondition() },
                    sourceWidgetId = WIDGET_ID,
                    bufferMinutes = req.bufferMinutes,
                    scheduleLate = req.scheduleLate,
                    zone = req.zone,
                    fixedStartMillis = fixedStart,
                    fixedEndMillis = fixedEnd,
                    colorArgb = req.colorArgb
                )
            )
        }
        val skippedCount = tasks.count { completions.isSkipped(it.id) }
        AppLogger.i(TAG, "syncToRegistry: registered ${tasks.size - skippedCount} tasks (${skippedCount} skipped)")
    }
}
