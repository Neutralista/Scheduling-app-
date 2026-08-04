package com.waypoint.app.planner

import android.content.Context
import com.waypoint.app.AppLogger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Persists TaskExecution records keyed by taskId.
 * Only keeps executions for the current day — resets automatically at midnight.
 */
class TaskExecutionStore(context: Context) {

    private val prefs = context.getSharedPreferences("waypoint_task_executions", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "TaskExecutionStore"
        private const val KEY_DATE = "exec_date"
    }

    private fun todayStr(): String = LocalDate.now().toString()

    private fun ensureToday() {
        if (prefs.getString(KEY_DATE, null) != todayStr()) {
            prefs.edit().clear().putString(KEY_DATE, todayStr()).apply()
        }
    }

    fun start(taskId: String): TaskExecution {
        ensureToday()
        val exec = TaskExecution(taskId = taskId, startMillis = System.currentTimeMillis())
        prefs.edit().putString(taskId, json.encodeToString(exec)).apply()
        AppLogger.i(TAG, "start: taskId=$taskId")
        return exec
    }

    fun stop(taskId: String): TaskExecution? {
        ensureToday()
        val raw = prefs.getString(taskId, null) ?: return null
        return try {
            val exec = json.decodeFromString<TaskExecution>(raw)
            val finished = exec.copy(endMillis = System.currentTimeMillis())
            prefs.edit().putString(taskId, json.encodeToString(finished)).apply()
            AppLogger.i(TAG, "stop: taskId=$taskId measuredMinutes=${finished.measuredMinutes}")
            finished
        } catch (_: Exception) { null }
    }

    fun startSubtask(taskId: String, subtaskId: String) {
        ensureToday()
        val raw = prefs.getString(taskId, null) ?: return
        try {
            val exec = json.decodeFromString<TaskExecution>(raw)
            val newSub = SubtaskExecution(subtaskId = subtaskId, startMillis = System.currentTimeMillis())
            val updated = exec.copy(subtaskExecutions = exec.subtaskExecutions + newSub)
            prefs.edit().putString(taskId, json.encodeToString(updated)).apply()
        } catch (_: Exception) {}
    }

    fun stopSubtask(taskId: String, subtaskId: String) {
        ensureToday()
        val raw = prefs.getString(taskId, null) ?: return
        try {
            val exec = json.decodeFromString<TaskExecution>(raw)
            val updatedSubs = exec.subtaskExecutions.map { sub ->
                if (sub.subtaskId == subtaskId && sub.endMillis == null)
                    sub.copy(endMillis = System.currentTimeMillis())
                else sub
            }
            prefs.edit().putString(taskId, json.encodeToString(exec.copy(subtaskExecutions = updatedSubs))).apply()
        } catch (_: Exception) {}
    }

    fun get(taskId: String): TaskExecution? {
        ensureToday()
        val raw = prefs.getString(taskId, null) ?: return null
        return try { json.decodeFromString<TaskExecution>(raw) } catch (_: Exception) { null }
    }

    fun getRunning(): TaskExecution? {
        ensureToday()
        return loadAll().firstOrNull { it.isRunning }
    }

    fun loadAll(): List<TaskExecution> {
        ensureToday()
        return prefs.all
            .filter { it.key != KEY_DATE }
            .values
            .mapNotNull { raw ->
                try { json.decodeFromString<TaskExecution>(raw as? String ?: return@mapNotNull null) }
                catch (_: Exception) { null }
            }
    }

    fun clear(taskId: String) {
        prefs.edit().remove(taskId).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
