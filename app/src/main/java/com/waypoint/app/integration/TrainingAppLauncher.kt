package com.waypoint.app.integration

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * The "open in Might" (Training-app) affordance shown on blocks Training-app created (see
 * [ExternalBlockDefinitionReceiver] and WAYPOINT_INTEGRATION.md) — jumps straight to the workout
 * that block mirrors via the deep link Training-app registers for `trainingapp://workout/{id}`.
 */
private const val TRAINING_APP_PACKAGE = "com.neutralista.trainingapp"
private const val WORKOUT_BLOCK_ID_PREFIX = "training-app-workout-"

/** The workout id if [blockId] is one Training-app owns, else null — also gates showing the icon. */
fun trainingAppWorkoutId(blockId: String): String? =
    blockId.takeIf { it.startsWith(WORKOUT_BLOCK_ID_PREFIX) }?.removePrefix(WORKOUT_BLOCK_ID_PREFIX)

fun openTrainingAppWorkout(context: Context, workoutId: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("trainingapp://workout/$workoutId")).apply {
        setPackage(TRAINING_APP_PACKAGE)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Might isn't installed", Toast.LENGTH_SHORT).show()
    }
}
