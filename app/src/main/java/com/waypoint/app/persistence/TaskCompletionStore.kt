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

    fun markDone(id: String) = markDoneAt(id, System.currentTimeMillis())

    /** Marks [id] done as of [whenMs] — lets a completion be logged for a time other than
     *  "right now" (e.g. something that actually finished a bit earlier). */
    fun markDoneAt(id: String, whenMs: Long) {
        val done = loadDoneIds().toMutableSet().apply { add(id) }
        prefs.edit()
            .putString("cycle_id", getCycleId())
            .putStringSet("done_ids", done)
            .putLong("done_at_$id", whenMs)
            .apply()
    }

    /** When [id] was marked done, or null if it isn't done or predates this field existing. */
    fun getDoneAt(id: String): Long? {
        if (!ensureCycle()) return null
        return prefs.getLong("done_at_$id", -1L).takeIf { it >= 0 }
    }

    fun unmarkDone(id: String) {
        val done = loadDoneIds().toMutableSet().apply { remove(id) }
        prefs.edit()
            .putString("cycle_id", getCycleId())
            .putStringSet("done_ids", done)
            .remove("done_at_$id")
            .apply()
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

    /**
     * Called when a new cycle opens at [sinceMs]. That wake can be in the past (a confirmed
     * wake, or a stale cycle closed after the fact), so done marks made since then belong to
     * the new cycle and are kept; everything older — and all skips, which have no time — is
     * cleared.
     */
    fun retainDoneSince(sinceMs: Long) {
        val kept = (prefs.getStringSet("done_ids", emptySet()) ?: emptySet())
            .mapNotNull { id -> prefs.getLong("done_at_$id", -1L).takeIf { it >= sinceMs }?.let { id to it } }
        val editor = prefs.edit().clear()
            .putString("cycle_id", getCycleId())
            .putStringSet("done_ids", kept.map { it.first }.toSet())
        kept.forEach { (id, at) -> editor.putLong("done_at_$id", at) }
        editor.apply()
    }
}
