package com.waypoint.app.persistence

import android.content.Context
import java.time.LocalDate

/** Tracks consecutive-day streaks keyed by an arbitrary string. */
class StreakStore(context: Context) {
    private val prefs = context.getSharedPreferences("waypoint_streaks", Context.MODE_PRIVATE)

    fun get(key: String): Int = prefs.getInt("${key}_count", 0)

    fun lastDate(key: String): String? = prefs.getString("${key}_date", null)

    /**
     * Marks today as done for [key]. Increments the streak if yesterday was the last date,
     * resets to 1 if the streak was broken, and is a no-op if already called today.
     * Returns the current streak count.
     */
    fun increment(key: String): Int {
        val today = LocalDate.now().toString()
        val lastDate = prefs.getString("${key}_date", null)
        if (lastDate == today) return prefs.getInt("${key}_count", 1)

        val yesterday = LocalDate.now().minusDays(1).toString()
        val newCount = if (lastDate == yesterday) prefs.getInt("${key}_count", 0) + 1 else 1
        prefs.edit().putInt("${key}_count", newCount).putString("${key}_date", today).apply()
        return newCount
    }

    fun reset(key: String) {
        prefs.edit().remove("${key}_count").remove("${key}_date").apply()
    }
}
