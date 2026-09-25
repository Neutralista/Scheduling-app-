package com.waypoint.app.persistence

import android.content.Context
import java.time.LocalDate

/**
 * The dates each task was done on, kept across wakes (TaskCompletionStore forgets at every new
 * wake). Lets "N times per period" count what's already been done this period. A year is kept.
 */
class TaskDoneHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("waypoint_task_done_history", Context.MODE_PRIVATE)

    private companion object {
        const val KEEP_DAYS = 366L
    }

    fun add(taskId: String, date: String) {
        val cutoff = LocalDate.now().minusDays(KEEP_DAYS).toString()
        // ISO dates compare correctly as strings.
        val kept = dates(taskId).filter { it >= cutoff }.toSet() + date
        prefs.edit().putStringSet(taskId, kept).apply()
    }

    fun remove(taskId: String, date: String) {
        prefs.edit().putStringSet(taskId, dates(taskId) - date).apply()
    }

    /** "yyyy-MM-dd" dates [taskId] was done on. */
    fun dates(taskId: String): Set<String> = prefs.getStringSet(taskId, emptySet()).orEmpty().toSet()

    fun forget(taskId: String) {
        prefs.edit().remove(taskId).apply()
    }
}
