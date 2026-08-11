package com.waypoint.app.planner

import android.content.Context
import com.waypoint.app.AppLogger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Persists TaskExecution records keyed by date + taskId (exec_{date}_{taskId}).
 * History is kept for 30 days. Only today's entries are touched by start/stop/get/getRunning.
 */
class TaskExecutionStore(context: Context) {

    private val prefs = context.getSharedPreferences("waypoint_task_executions", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "TaskExecutionStore"
        private const val HISTORY_DAYS = 30
    }

    private fun todayStr(): String = LocalDate.now().toString()

    private fun dateOf(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun key(date: String, taskId: String) = "exec_${date}_${taskId}"
    private fun key(taskId: String) = key(todayStr(), taskId)

    fun start(taskId: String): TaskExecution {
        val exec = TaskExecution(taskId = taskId, startMillis = System.currentTimeMillis())
        prefs.edit().putString(key(taskId), json.encodeToString(exec)).apply()
        AppLogger.i(TAG, "start: taskId=$taskId")
        return exec
    }

    fun stop(taskId: String): TaskExecution? {
        val raw = prefs.getString(key(taskId), null) ?: return null
        return try {
            val exec = json.decodeFromString<TaskExecution>(raw)
            val finished = exec.copy(endMillis = System.currentTimeMillis())
            prefs.edit().putString(key(taskId), json.encodeToString(finished)).apply()
            AppLogger.i(TAG, "stop: taskId=$taskId measuredMinutes=${finished.measuredMinutes}")
            finished
        } catch (_: Exception) { null }
    }

    fun startSubtask(taskId: String, subtaskId: String) {
        val raw = prefs.getString(key(taskId), null) ?: return
        try {
            val exec = json.decodeFromString<TaskExecution>(raw)
            val newSub = SubtaskExecution(subtaskId = subtaskId, startMillis = System.currentTimeMillis())
            val updated = exec.copy(subtaskExecutions = exec.subtaskExecutions + newSub)
            prefs.edit().putString(key(taskId), json.encodeToString(updated)).apply()
        } catch (_: Exception) {}
    }

    fun stopSubtask(taskId: String, subtaskId: String) {
        val raw = prefs.getString(key(taskId), null) ?: return
        try {
            val exec = json.decodeFromString<TaskExecution>(raw)
            val updatedSubs = exec.subtaskExecutions.map { sub ->
                if (sub.subtaskId == subtaskId && sub.endMillis == null)
                    sub.copy(endMillis = System.currentTimeMillis())
                else sub
            }
            prefs.edit().putString(key(taskId), json.encodeToString(exec.copy(subtaskExecutions = updatedSubs))).apply()
        } catch (_: Exception) {}
    }

    fun get(taskId: String): TaskExecution? {
        val raw = prefs.getString(key(taskId), null) ?: return null
        return try { json.decodeFromString<TaskExecution>(raw) } catch (_: Exception) { null }
    }

    fun getRunning(): TaskExecution? {
        val prefix = "exec_${todayStr()}_"
        return prefs.all
            .filter { it.key.startsWith(prefix) }
            .values
            .mapNotNull { raw ->
                try { json.decodeFromString<TaskExecution>(raw as? String ?: return@mapNotNull null) }
                catch (_: Exception) { null }
            }
            .firstOrNull { it.isRunning }
    }

    /** All executions across the last [HISTORY_DAYS] days, newest first. */
    fun loadAll(): List<TaskExecution> {
        val cutoff = LocalDate.now().minusDays(HISTORY_DAYS.toLong())
        return prefs.all
            .filter { (k, _) ->
                if (!k.startsWith("exec_")) return@filter false
                val datePart = k.removePrefix("exec_").substringBefore("_")
                try { !LocalDate.parse(datePart).isBefore(cutoff) } catch (_: Exception) { false }
            }
            .values
            .mapNotNull { raw ->
                try { json.decodeFromString<TaskExecution>(raw as? String ?: return@mapNotNull null) }
                catch (_: Exception) { null }
            }
            .sortedByDescending { it.startMillis }
    }

    /**
     * Delete the execution for [taskId]. Pass [startMillis] to target a historical entry
     * on the correct date; omit to delete today's entry.
     */
    fun clear(taskId: String, startMillis: Long? = null) {
        val date = if (startMillis != null) dateOf(startMillis) else todayStr()
        prefs.edit().remove(key(date, taskId)).apply()
    }

    fun update(execution: TaskExecution) {
        val date = dateOf(execution.startMillis)
        prefs.edit().putString(key(date, execution.taskId), json.encodeToString(execution)).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    fun pruneOld() {
        val cutoff = LocalDate.now().minusDays(HISTORY_DAYS.toLong())
        val toRemove = prefs.all.keys.filter { k ->
            if (!k.startsWith("exec_")) return@filter false
            val datePart = k.removePrefix("exec_").substringBefore("_")
            try { LocalDate.parse(datePart).isBefore(cutoff) } catch (_: Exception) { false }
        }
        if (toRemove.isNotEmpty()) prefs.edit().apply { toRemove.forEach { remove(it) } }.apply()
    }
}
