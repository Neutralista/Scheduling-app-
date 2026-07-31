package com.waypoint.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.waypoint.app.AppLogger
import com.waypoint.app.planner.SleepCheckReceiver
import com.waypoint.app.planner.SleepLogStore

class SleepActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.i(TAG, "onReceive: action=${intent.action}")
        if (intent.action == ACTION_ENTER_SLEEP_MODE) {
            val logStore = SleepLogStore(context)
            val prevState = logStore.getSleepModeState()
            logStore.enterSleepMode()
            AppLogger.i(TAG, "enterSleepMode: prevState=$prevState → MONITORING")
            SleepCheckReceiver.scheduleNextCheck(context)
            AppLogger.i(TAG, "scheduleNextCheck: done")
        }
    }

    companion object {
        private const val TAG = "SleepActionReceiver"
        const val ACTION_ENTER_SLEEP_MODE = "com.waypoint.app.ENTER_SLEEP_MODE"
    }
}
