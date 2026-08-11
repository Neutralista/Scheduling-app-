package com.waypoint.app.notification

import android.content.Context
import androidx.work.WorkManager

object ReminderScheduler {

    private const val WORK_NAME = "waypoint_daily_reminder"

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
