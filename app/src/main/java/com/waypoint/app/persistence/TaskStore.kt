package com.waypoint.app.persistence

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.UUID

@Serializable
data class TaskEntry(
    val id: String,
    val title: String,
    val done: Boolean = false
)

class TaskStore(context: Context) {
    private val prefs = context.getSharedPreferences("waypoint_tasks", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private fun todayKey() = "tasks_${LocalDate.now()}"

    fun loadToday(): List<TaskEntry> {
        val raw = prefs.getString(todayKey(), null) ?: return emptyList()
        return try { json.decodeFromString(raw) } catch (_: Exception) { emptyList() }
    }

    private fun save(tasks: List<TaskEntry>) {
        prefs.edit().putString(todayKey(), json.encodeToString(tasks)).apply()
        pruneOld()
    }

    fun add(title: String): List<TaskEntry> {
        val tasks = loadToday() + TaskEntry(id = UUID.randomUUID().toString(), title = title)
        save(tasks)
        return tasks
    }

    fun setDone(id: String, done: Boolean): List<TaskEntry> {
        val tasks = loadToday().map { if (it.id == id) it.copy(done = done) else it }
        save(tasks)
        return tasks
    }

    fun delete(id: String): List<TaskEntry> {
        val tasks = loadToday().filter { it.id != id }
        save(tasks)
        return tasks
    }

    fun clearCompleted(): List<TaskEntry> {
        val tasks = loadToday().filter { !it.done }
        save(tasks)
        return tasks
    }

    private fun pruneOld() {
        val cutoff = LocalDate.now().minusDays(7)
        val toRemove = prefs.all.keys.filter { key ->
            if (!key.startsWith("tasks_")) return@filter false
            try { LocalDate.parse(key.removePrefix("tasks_")).isBefore(cutoff) }
            catch (_: Exception) { false }
        }
        if (toRemove.isNotEmpty()) prefs.edit().apply { toRemove.forEach { remove(it) } }.apply()
    }
}
