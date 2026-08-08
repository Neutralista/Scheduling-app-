package com.waypoint.app.persistence

import android.content.Context

/** Tracks which task IDs are marked done or skipped for the current wake cycle. Resets when the cycle ID changes. */
class TaskCompletionStore(context: Context) {
    private val prefs = context.getSharedPreferences("waypoint_task_completions", Context.MODE_PRIVATE)

    /** Injected after construction; returns the current cycle's ID (empty string = no cycle). */
    var getCycleId: () -> String = { "" }

    private fun ensureCycle(): Boolean {
        val cycleId = getCycleId()
        if (prefs.getString("cycle_id", null) != cycleId) {
            prefs.edit().clear().putString("cycle_id", cycleId).apply()
            return false
        }
        return true
    }

    private fun loadDoneIds(): Set<String> {
        if (!ensureCycle()) return emptySet()
        return prefs.getStringSet("done_ids", emptySet()) ?: emptySet()
    }

    private fun loadSkippedIds(): Set<String> {
        if (!ensureCycle()) return emptySet()
        return prefs.getStringSet("skip_ids", emptySet()) ?: emptySet()
    }

    fun isDone(id: String): Boolean = id in loadDoneIds()
    fun isSkipped(id: String): Boolean = id in loadSkippedIds()

    fun markDone(id: String) {
        val done = loadDoneIds().toMutableSet().apply { add(id) }
        prefs.edit().putString("cycle_id", getCycleId()).putStringSet("done_ids", done).apply()
    }

    fun unmarkDone(id: String) {
        val done = loadDoneIds().toMutableSet().apply { remove(id) }
        prefs.edit().putString("cycle_id", getCycleId()).putStringSet("done_ids", done).apply()
    }

    fun skipTask(id: String) {
        val skipped = loadSkippedIds().toMutableSet().apply { add(id) }
        prefs.edit().putString("cycle_id", getCycleId()).putStringSet("skip_ids", skipped).apply()
    }

    fun unskipTask(id: String) {
        val skipped = loadSkippedIds().toMutableSet().apply { remove(id) }
        prefs.edit().putString("cycle_id", getCycleId()).putStringSet("skip_ids", skipped).apply()
    }

    fun getDoneIds(): Set<String> = loadDoneIds()
    fun getSkippedIds(): Set<String> = loadSkippedIds()

    /** Clear all marks immediately (called when a new cycle opens). */
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
