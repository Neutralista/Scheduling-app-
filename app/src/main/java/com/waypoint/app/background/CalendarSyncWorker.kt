package com.waypoint.app.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.waypoint.app.WaypointApplication
import com.waypoint.app.planner.SleepCalendarSync
import com.waypoint.app.planner.SleepLogStore
import java.util.concurrent.TimeUnit

class CalendarSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as WaypointApplication
        val logStore = SleepLogStore(app)
        SleepCalendarSync.syncLogToCalendar(app, logStore)
        app.env.sleepStore.syncToRegistry(app.env.eventPlanner)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "waypoint_calendar_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CalendarSyncWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
