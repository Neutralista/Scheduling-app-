package com.waypoint.app.planner

import android.content.Context
import com.waypoint.app.AppLogger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Persists TaskExecution records keyed by the date they started + taskId (exec_{date}_{taskId}).
 * History is kept for 30 days. start() writes today's entry; stop/get/getRunning and the subtask
 * calls look at today's and yesterday's, so a task started before midnight is still found (and
 * can be stopped) after it — a wake cycle doesn't end at midnight.
 */
class TaskExecutionStore(context: Context) {

    private val prefs = context.getSharedPreferences("waypoint_task_executions", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "TaskExecutionStore"
        private const val HISTORY_DAYS = 30
    }

    private fun todayStr(): String = LocalDate.now().toString()

    private fun recentDates(): List<String> = LocalDate.now().let { listOf(it.toString(), it.minusDays(1).toString()) }

    private fun dateOf(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun key(date: String, taskId: String) = "exec_${date}_${taskId}"

    private fun decode(raw: String?): TaskExecution? =
        raw?.let { try { json.decodeFromString<TaskExecution>(it) } catch (_: Exception) { null } }

    /** The most recently started of today's and yesterday's executions of [taskId], with its key. */
    private fun latestEntry(taskId: String): Pair<String, TaskExecution>? =
        recentDates()
            .mapNotNull { date -> key(date, taskId).let { k -> decode(prefs.getString(k, null))?.let { k to it } } }
            .maxByOrNull { it.second.startMillis }

    private fun put(key: String, exec: TaskExecution) {
        prefs.edit().putString(key, json.encodeToString(exec)).apply()
    }

    fun start(taskId: String): TaskExecution {
        val exec = TaskExecution(taskId = taskId, startMillis = System.currentTimeMillis())
        put(key(todayStr(), taskId), exec)
        AppLogger.i(TAG, "start: taskId=$taskId")
        return exec
    }

    fun stop(taskId: String): TaskExecution? {
        val (k, exec) = latestEntry(taskId)?.takeIf { it.second.isRunning } ?: return null
        val finished = exec.copy(endMillis = System.currentTimeMillis())
        put(k, finished)
        AppLogger.i(TAG, "stop: taskId=$taskId measuredMinutes=${finished.measuredMinutes}")
        return finished
    }

    fun startSubtask(taskId: String, subtaskId: String) {
        val (k, exec) = latestEntry(taskId)?.takeIf { it.second.isRunning } ?: return
        val newSub = SubtaskExecution(subtaskId = subtaskId, startMillis = System.currentTimeMillis())
        put(k, exec.copy(subtaskExecutions = exec.subtaskExecutions + newSub))
    }

    fun stopSubtask(taskId: String, subtaskId: String) {
        val (k, exec) = latestEntry(taskId)?.takeIf { it.second.isRunning } ?: return
        val updatedSubs = exec.subtaskExecutions.map { sub ->
            if (sub.subtaskId == subtaskId && sub.endMillis == null)
                sub.copy(endMillis = System.currentTimeMillis())
            else sub
        }
        put(k, exec.copy(subtaskExecutions = updatedSubs))
    }

    /** The latest execution of [taskId] started today or yesterday, running or finished. */
    fun get(taskId: String): TaskExecution? = latestEntry(taskId)?.second

    fun getRunning(): TaskExecution? {
        val prefixes = recentDates().map { "exec_${it}_" }
        return prefs.all
            .filter { (k, _) -> prefixes.any { k.startsWith(it) } }
            .values
            .mapNotNull { decode(it as? String) }
            .filter { it.isRunning }
            .maxByOrNull { it.startMillis }
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
