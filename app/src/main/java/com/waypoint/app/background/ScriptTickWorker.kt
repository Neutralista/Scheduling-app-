package com.waypoint.app.background

import android.content.Context
import androidx.work.CoroutineWorker
import com.waypoint.app.alarm.AlarmBlockSync
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.waypoint.app.WaypointApplication
import com.waypoint.app.script.ScriptRegistry
import com.waypoint.app.script.ScriptState
import com.waypoint.app.script.ScriptedModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class ScriptTickWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as WaypointApplication

        val states: Map<String, ScriptState> = withContext(Dispatchers.IO) {
            app.scriptStateStore.allStates().first()
        }

        // Refresh location cache so scripts see fresh coordinates
        runCatching { app.env.location.refreshCache() }

        // Keep block-synced alarms' enabled state current — without this, an alarm linked to a
        // block only re-syncs on boot or app-open, and can sit stale for days otherwise.
        runCatching { AlarmBlockSync.sync(app) }

        withContext(Dispatchers.Default) {
            ScriptRegistry.all()
                .filterIsInstance<ScriptedModule>()
                .forEach { script ->
                    val state    = states[script.id] ?: ScriptState()
                    val newState = runCatching { script.tick(state) }.getOrDefault(state)
                    if (newState != state) app.env.setScriptState(script.id, newState)
                }
            // Re-sync task manager so any task submissions from JS onTick hooks are applied
            runCatching { app.env.taskManager.syncToRegistry() }
        }

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "waypoint_script_tick"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScriptTickWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
