package com.waypoint.app.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.waypoint.app.AppLogger
import com.waypoint.app.planner.BlockSessionLog
import com.waypoint.app.planner.BlockSessionLogStore
import com.waypoint.app.planner.BlockTaskMeasurement
import com.waypoint.app.WaypointApplication
import java.time.LocalDate
import com.waypoint.app.planner.NamedBlockStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Accepts a completed workout reported by Training-app (com.neutralista.trainingapp) and logs it
 * into Waypoint's own block-session history, so it shows up in the History tab like any other
 * gym/activity block. One-way: Training-app (Might) reports the day's session as sets are logged,
 * each time replacing the day's entry.
 *
 * With per-exercise times (the parallel arrays EXTRA_EXERCISE_IDS / _START_MS / _END_MS), each
 * exercise is logged as a measurement of its block task ("$blockId-$exerciseId") and ticked done
 * at its end, so it shows as a completed task at the time it was actually done: the exercises are
 * completed in Might, and Waypoint shows what happened.
 *
 * Gated by the ACTION_LOG_BLOCK_SESSION permission this app declares (protectionLevel="normal"),
 * which the sender must hold — see AndroidManifest.xml.
 */
class ExternalBlockSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOG_BLOCK_SESSION) return

        val blockId = intent.getStringExtra(EXTRA_BLOCK_ID) ?: return
        // The permission is "normal", so any app can hold it; only Training-app's own blocks.
        if (trainingAppWorkoutId(blockId) == null) {
            AppLogger.w(TAG, "onReceive: ignoring session for non-Training-app block $blockId")
            return
        }
        val blockName = intent.getStringExtra(EXTRA_BLOCK_NAME) ?: return
        val startMs = intent.getLongExtra(EXTRA_START_MS, -1L)
        val endMs = intent.getLongExtra(EXTRA_END_MS, -1L)
        if (startMs < 0 || endMs < 0 || endMs <= startMs) {
            AppLogger.w(TAG, "onReceive: ignoring malformed session (start=$startMs, end=$endMs)")
            return
        }
        var tasksCompleted = intent.getIntExtra(EXTRA_TASKS_COMPLETED, 0)
        var tasksTotal = intent.getIntExtra(EXTRA_TASKS_TOTAL, 0)

        // Per-exercise times, when this Might sends them; older versions don't.
        val ids = intent.getStringArrayExtra(EXTRA_EXERCISE_IDS)
        val starts = intent.getLongArrayExtra(EXTRA_EXERCISE_START_MS)
        val ends = intent.getLongArrayExtra(EXTRA_EXERCISE_END_MS)
        val blockTaskIds = NamedBlockStore(context).loadTasksForBlock(blockId).map { it.id }.toSet()
        val measurements = if (ids != null && starts != null && ends != null &&
            ids.size == starts.size && ids.size == ends.size
        ) {
            ids.indices.mapNotNull { i ->
                val taskId = "$blockId-${ids[i]}"
                if (taskId !in blockTaskIds || ends[i] <= starts[i]) null
                else BlockTaskMeasurement(taskId = taskId, startMs = starts[i], endMs = ends[i])
            }
        } else emptyList()
        if (measurements.isNotEmpty()) {
            // The block's checklist counts exercises done, not sets.
            tasksCompleted = measurements.map { it.taskId }.distinct().size
            tasksTotal = maxOf(blockTaskIds.size, tasksCompleted)
        }

        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(startMs))

        // Training-app reports the whole day each time, from the first set to the last, so a
        // later sync replaces the day's entry instead of adding a second one.
        BlockSessionLogStore(context).replaceDay(
            BlockSessionLog(
                blockId = blockId,
                blockName = blockName,
                colorArgb = NamedBlockStore(context).loadBlock(blockId)?.colorArgb,
                date = dateKey,
                startedAtMs = startMs,
                endedAtMs = endMs,
                tasksCompleted = tasksCompleted,
                tasksTotal = tasksTotal,
                taskMeasurements = measurements
            )
        )
        // Tick today's exercises done, at when each finished (earlier days' ticks have lapsed).
        if (dateKey == LocalDate.now().toString() && measurements.isNotEmpty()) {
            runCatching {
                val taskManager = (context.applicationContext as WaypointApplication).env.taskManager
                measurements.forEach { m ->
                    if (!taskManager.isDone(m.taskId)) taskManager.markDoneAt(m.taskId, m.endMs)
                }
            }.onFailure { AppLogger.e(TAG, "onReceive: couldn't tick exercises done", it) }
        }
        AppLogger.i(TAG, "onReceive: logged '$blockName' ($startMs-$endMs)")
    }

    companion object {
        const val ACTION_LOG_BLOCK_SESSION = "com.waypoint.app.LOG_BLOCK_SESSION"
        const val EXTRA_BLOCK_ID = "blockId"
        const val EXTRA_BLOCK_NAME = "blockName"
        const val EXTRA_START_MS = "startMs"
        const val EXTRA_END_MS = "endMs"
        const val EXTRA_TASKS_COMPLETED = "tasksCompleted"
        const val EXTRA_TASKS_TOTAL = "tasksTotal"
        const val EXTRA_EXERCISE_IDS = "exerciseIds"
        const val EXTRA_EXERCISE_START_MS = "exerciseStartMs"
        const val EXTRA_EXERCISE_END_MS = "exerciseEndMs"
        private const val TAG = "ExternalBlockSession"
    }
}
