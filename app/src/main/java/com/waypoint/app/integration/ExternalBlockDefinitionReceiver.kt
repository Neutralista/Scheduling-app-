package com.waypoint.app.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.waypoint.app.AppLogger
import com.waypoint.app.planner.ExternalBlockExercise
import com.waypoint.app.planner.NamedBlockStore

/**
 * Accepts a workout-block definition pushed by Training-app (com.neutralista.trainingapp) and
 * creates/updates a real, schedulable [com.waypoint.app.planner.NamedBlock] for it in Waypoint —
 * unlike [ExternalBlockSessionReceiver] (a retroactive history-only log), this is what actually
 * puts the workout on Waypoint's live day-planner timeline. Fixed on [EXTRA_RECURRING_DAYS] when
 * given, otherwise floating ("any day, planner decides").
 *
 * Sent on every visit to that workout in Training-app and again after each logged set (with
 * per-exercise durations refined from logged history) — see WAYPOINT_INTEGRATION.md. Every call
 * is the same idempotent upsert: matching by blockId, updating each exercise task's title,
 * duration and order, and removing any task whose exercise is no longer present. An empty
 * exercise list (sent when the workout is deleted) deletes the block.
 *
 * Gated by the same ACTION_LOG_BLOCK_SESSION permission as [ExternalBlockSessionReceiver] — same
 * sender, same trust boundary — and limited to Training-app's own block ids.
 */
class ExternalBlockDefinitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UPSERT_TRAINING_BLOCK) return

        val blockId = intent.getStringExtra(EXTRA_BLOCK_ID) ?: return
        // The permission is "normal", so any app can hold it — without this, any app could rename,
        // rewrite or (with no exercises) delete any block, including ones made in Waypoint.
        if (trainingAppWorkoutId(blockId) == null) {
            AppLogger.w(TAG, "onReceive: ignoring non-Training-app block $blockId")
            return
        }
        val blockName = intent.getStringExtra(EXTRA_BLOCK_NAME) ?: return
        val exerciseIds = intent.getStringArrayExtra(EXTRA_EXERCISE_IDS) ?: emptyArray()
        val exerciseNames = intent.getStringArrayExtra(EXTRA_EXERCISE_NAMES) ?: emptyArray()
        val exerciseMinutes = intent.getIntArrayExtra(EXTRA_EXERCISE_MINUTES) ?: IntArray(0)
        val recurringDays = (intent.getIntArrayExtra(EXTRA_RECURRING_DAYS) ?: IntArray(0)).toList()

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

        NamedBlockStore(context).upsertExternalBlock(blockId, blockName, exercises, recurringDays)
        AppLogger.i(
            TAG,
            "onReceive: synced '$blockName' ($blockId) with ${exercises.size} exercises, " +
                "recurringDays=$recurringDays"
        )
    }

    companion object {
        const val ACTION_UPSERT_TRAINING_BLOCK = "com.waypoint.app.UPSERT_TRAINING_BLOCK"
        const val EXTRA_BLOCK_ID = "blockId"
        const val EXTRA_BLOCK_NAME = "blockName"
        const val EXTRA_EXERCISE_IDS = "exerciseIds"
        const val EXTRA_EXERCISE_NAMES = "exerciseNames"
        const val EXTRA_EXERCISE_MINUTES = "exerciseMinutes"
        const val EXTRA_RECURRING_DAYS = "recurringDays"
        private const val TAG = "ExternalBlockDef"
    }
}
