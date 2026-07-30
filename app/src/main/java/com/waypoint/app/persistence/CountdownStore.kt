package com.waypoint.app.persistence

import android.content.Context

/** Persistent named deadlines — timestamps scripts can set and query. */
class CountdownStore(context: Context) {
    private val prefs = context.getSharedPreferences("waypoint_countdowns", Context.MODE_PRIVATE)

    /** Store a deadline. [endMs] is epoch-milliseconds. */
    fun set(key: String, endMs: Long) { prefs.edit().putLong(key, endMs).apply() }

    /** Raw end timestamp, or -1 if not set. */
    fun endMs(key: String): Long = prefs.getLong(key, -1L)

    /** Milliseconds remaining until deadline. Negative means overdue. Long.MIN_VALUE if not set. */
    fun remaining(key: String): Long {
        val end = endMs(key)
        return if (end < 0) Long.MIN_VALUE else end - System.currentTimeMillis()
    }

    /** Minutes remaining, rounded down. Negative means overdue. Int.MIN_VALUE if not set. */
    fun remainingMinutes(key: String): Int {
        val ms = remaining(key)
        return if (ms == Long.MIN_VALUE) Int.MIN_VALUE else (ms / 60_000L).toInt()
    }

    fun clear(key: String) { prefs.edit().remove(key).apply() }

    fun keys(): Set<String> = prefs.all.keys.toSet()
}
