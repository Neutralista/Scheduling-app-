package com.waypoint.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
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

/**
 * In-process log with file persistence so entries survive app crashes.
 *
 * Call [init] once from Application.onCreate() before anything else logs.
 * Each [log] call appends a line to the current-session file synchronously
 * inside the write lock — slow, but ensures every entry is on disk before
 * the call returns, which is exactly what crash diagnostics need.
 */
object AppLogger {
    private const val MAX_CURRENT = 400
    private const val MAX_LAST    = 600

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_CURRENT)
    private var logFile: File? = null

    private val _entries     = MutableStateFlow<List<LogEntry>>(emptyList())
    private val _lastSession = MutableStateFlow<List<LogEntry>>(emptyList())

    val entries:     StateFlow<List<LogEntry>> = _entries.asStateFlow()
    val lastSession: StateFlow<List<LogEntry>> = _lastSession.asStateFlow()

    // ── Init ──────────────────────────────────────────────────────────────────

    fun init(filesDir: File) {
        val current = File(filesDir, "waypoint_current.log")
        val last    = File(filesDir, "waypoint_last_session.log")

        // Rotate: current → last, then start fresh
        if (current.exists()) {
            last.delete()
            current.renameTo(last)
        }

        // Load last session into memory (capped so we don't OOM on huge files)
        if (last.exists()) {
            _lastSession.value = last.readLines()
                .takeLast(MAX_LAST)
                .mapNotNull { parseLine(it) }
        }

        // Create new current-session file
        current.parentFile?.mkdirs()
        current.createNewFile()
        logFile = current
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun i(tag: String, message: String) = log(LogLevel.I, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.W, tag, message)

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val msg = if (throwable != null)
            "$message: ${throwable.javaClass.simpleName}: ${throwable.message}"
        else message
        log(LogLevel.E, tag, msg)
    }

    fun clearCurrent() {
        synchronized(lock) {
            buffer.clear()
            logFile?.writeText("")
        }
        _entries.value = emptyList()
    }

    fun clearAll() {
        clearCurrent()
        _lastSession.value = emptyList()
    }

    /** Plain-text copy of both sessions, easy to paste into a bug report. */
    fun copyText(): String {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        fun List<LogEntry>.fmt() = joinToString("\n") { e ->
            "[${fmt.format(Date(e.millis))}] ${e.level}/${e.tag}: ${e.message}"
        }
        val sb = StringBuilder()
        val last = _lastSession.value
        if (last.isNotEmpty()) {
            sb.appendLine("=== Previous Session ===")
            sb.appendLine(last.fmt())
            sb.appendLine()
        }
        sb.appendLine("=== Current Session ===")
        val current = synchronized(lock) { buffer.toList() }
        sb.append(current.fmt())
        return sb.toString()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        val snapshot: List<LogEntry>
        synchronized(lock) {
            if (buffer.size >= MAX_CURRENT) buffer.removeFirst()
            buffer.addLast(entry)
            snapshot = buffer.toList()
            // Synchronous append — ensures on-disk before we return, surviving crashes
            try { logFile?.appendText(serializeLine(entry) + "\n") } catch (_: Exception) {}
        }
        _entries.value = snapshot
    }

    private fun serializeLine(e: LogEntry): String {
        val safeMsg = e.message.replace("\t", "  ").replace("\n", " ↵ ")
        return "${e.millis}\t${e.level.name}\t${e.tag}\t$safeMsg"
    }

    private fun parseLine(line: String): LogEntry? {
        val parts = line.split("\t", limit = 4)
        if (parts.size < 4) return null
        val millis = parts[0].toLongOrNull() ?: return null
        val level  = try { LogLevel.valueOf(parts[1]) } catch (_: Exception) { return null }
        return LogEntry(millis, level, parts[2], parts[3])
    }
}
