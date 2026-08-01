package com.waypoint.app.persistence

import android.content.Context
import java.time.LocalDate

/** Tracks which task IDs are marked done for the current calendar day. Auto-resets at midnight. */
class TaskCompletionStore(context: Context) {
    private val prefs = context.getSharedPreferences("waypoint_task_completions", Context.MODE_PRIVATE)

    private fun todayStr(): String = LocalDate.now().toString()

    private fun loadDoneIds(): Set<String> {
        if (prefs.getString("date", null) != todayStr()) {
            prefs.edit().clear().putString("date", todayStr()).apply()
            return emptySet()
        }
        return prefs.getStringSet("done_ids", emptySet()) ?: emptySet()
    }

    fun isDone(id: String): Boolean = id in loadDoneIds()

    fun markDone(id: String) {
        val done = loadDoneIds().toMutableSet().apply { add(id) }
        prefs.edit().putString("date", todayStr()).putStringSet("done_ids", done).apply()
    }

    fun unmarkDone(id: String) {
        val done = loadDoneIds().toMutableSet().apply { remove(id) }
        prefs.edit().putString("date", todayStr()).putStringSet("done_ids", done).apply()
    }

    fun getDoneIds(): Set<String> = loadDoneIds()
}
