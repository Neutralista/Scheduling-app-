package com.waypoint.app.planner

import android.content.Context

class CalendarPrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("waypoint_cal_event_prefs", Context.MODE_PRIVATE)

    fun reservesTime(eventId: Long): Boolean = prefs.getBoolean("rt_$eventId", true)

    fun setReservesTime(eventId: Long, reserves: Boolean) {
        prefs.edit().putBoolean("rt_$eventId", reserves).apply()
    }

    fun clear(eventId: Long) {
        prefs.edit().remove("rt_$eventId").apply()
    }
}
