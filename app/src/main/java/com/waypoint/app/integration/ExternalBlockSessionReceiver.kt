package com.waypoint.app.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.waypoint.app.AppLogger
import com.waypoint.app.planner.BlockSessionLog
import com.waypoint.app.planner.BlockSessionLogStore
import com.waypoint.app.planner.NamedBlockStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Accepts a completed workout reported by Training-app (com.neutralista.trainingapp) and logs it
 * into Waypoint's own block-session history, so it shows up in the History tab like any other
 * gym/activity block. One-way and retroactive only: Training-app reports sessions after they
 * finish, there is no live "in progress" state and no attempt to reconcile edits or deletions
 * made on either side afterward.
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
        val tasksCompleted = intent.getIntExtra(EXTRA_TASKS_COMPLETED, 0)
        val tasksTotal = intent.getIntExtra(EXTRA_TASKS_TOTAL, 0)

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
                tasksTotal = tasksTotal
            )
        )
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
        private const val TAG = "ExternalBlockSession"
    }
}
