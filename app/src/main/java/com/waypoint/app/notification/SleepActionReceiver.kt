package com.waypoint.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.waypoint.app.planner.SleepCheckReceiver
import com.waypoint.app.planner.SleepLogStore

class SleepActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_ENTER_SLEEP_MODE) {
            SleepLogStore(context).enterSleepMode()
            SleepCheckReceiver.scheduleNextCheck(context)
        }
    }

    companion object {
        const val ACTION_ENTER_SLEEP_MODE = "com.waypoint.app.ENTER_SLEEP_MODE"
    }
}
