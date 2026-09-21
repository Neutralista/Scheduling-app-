package com.waypoint.app.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.waypoint.app.AppLogger
import com.waypoint.app.planner.ExternalBlockExercise
import com.waypoint.app.planner.NamedBlockStore

/**
 * Accepts a workout-block definition pushed by Training-app (com.neutralista.trainingapp) and
 * creates/updates a real, schedulable floating [com.waypoint.app.planner.NamedBlock] for it in
 * Waypoint — unlike [ExternalBlockSessionReceiver] (a retroactive history-only log), this is what
 * actually puts the workout on Waypoint's live day-planner timeline.
 *
 * Sent once when a split day is first used (with Training-app's own initial time assumptions,
 * before any workout has been logged for it) and again after every completed workout (with
 * per-exercise durations averaged from logged history) — see WAYPOINT_INTEGRATION.md. Both cases
 * are the same idempotent upsert: matching by blockId, replacing the task list, and removing any
 * task whose exercise is no longer present.
 *
 * Gated by the same ACTION_LOG_BLOCK_SESSION permission as [ExternalBlockSessionReceiver] — same
 * sender, same trust boundary.
 */
class ExternalBlockDefinitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UPSERT_TRAINING_BLOCK) return

        val blockId = intent.getStringExtra(EXTRA_BLOCK_ID) ?: return
        val blockName = intent.getStringExtra(EXTRA_BLOCK_NAME) ?: return
        val exerciseIds = intent.getStringArrayExtra(EXTRA_EXERCISE_IDS) ?: emptyArray()
        val exerciseNames = intent.getStringArrayExtra(EXTRA_EXERCISE_NAMES) ?: emptyArray()
        val exerciseMinutes = intent.getIntArrayExtra(EXTRA_EXERCISE_MINUTES) ?: IntArray(0)

        if (exerciseIds.size != exerciseNames.size || exerciseIds.size != exerciseMinutes.size) {
            AppLogger.w(
                TAG,
                "onReceive: mismatched array lengths (ids=${exerciseIds.size} " +
                    "names=${exerciseNames.size} minutes=${exerciseMinutes.size}) — ignoring"
            )
            return
        }

        val exercises = exerciseIds.indices.map { i ->
            ExternalBlockExercise(
                externalId = exerciseIds[i],
                title = exerciseNames[i],
                durationMinutes = exerciseMinutes[i]
            )
        }

        NamedBlockStore(context).upsertExternalBlock(blockId, blockName, exercises)
        AppLogger.i(TAG, "onReceive: synced '$blockName' ($blockId) with ${exercises.size} exercises")
    }

    companion object {
        const val ACTION_UPSERT_TRAINING_BLOCK = "com.waypoint.app.UPSERT_TRAINING_BLOCK"
        const val EXTRA_BLOCK_ID = "blockId"
        const val EXTRA_BLOCK_NAME = "blockName"
        const val EXTRA_EXERCISE_IDS = "exerciseIds"
        const val EXTRA_EXERCISE_NAMES = "exerciseNames"
        const val EXTRA_EXERCISE_MINUTES = "exerciseMinutes"
        private const val TAG = "ExternalBlockDef"
    }
}
