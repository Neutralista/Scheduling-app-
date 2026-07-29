package com.waypoint.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { I, W, E }

data class LogEntry(
    val millis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
)

object AppLogger {
    private const val MAX = 400
    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun i(tag: String, message: String) = log(LogLevel.I, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.W, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val msg = if (throwable != null)
            "$message: ${throwable.javaClass.simpleName}: ${throwable.message}"
        else message
        log(LogLevel.E, tag, msg)
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        _entries.value = emptyList()
    }

    fun copyText(): String {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return synchronized(lock) { buffer.toList() }
            .joinToString("\n") { e ->
                "[${fmt.format(Date(e.millis))}] ${e.level}/${e.tag}: ${e.message}"
            }
    }

    private fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        val snapshot: List<LogEntry>
        synchronized(lock) {
            if (buffer.size >= MAX) buffer.removeFirst()
            buffer.addLast(entry)
            snapshot = buffer.toList()
        }
        _entries.value = snapshot
    }
}
