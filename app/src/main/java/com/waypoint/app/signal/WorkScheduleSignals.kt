package com.waypoint.app.signal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar

// ── Data model ──────────────────────────────────────────────────────────────

@Serializable
data class ShiftTime(val hour: Int, val minute: Int) {
    val displayString: String get() = "%02d:%02d".format(hour, minute)
    val totalMinutes: Int get() = hour * 60 + minute

    companion object {
        fun parse(text: String): ShiftTime? {
            val parts = text.trim().split(":").takeIf { it.size == 2 } ?: return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return ShiftTime(h, m)
        }
    }
}

@Serializable
data class DaySchedule(
    val isWork: Boolean = false,
    val shiftStart: ShiftTime? = null,
    val shiftEnd: ShiftTime? = null
) {
    /** True when the shift runs past midnight (e.g. 16:00 – 00:30). */
    val crossesMidnight: Boolean
        get() {
            val s = shiftStart ?: return false
            val e = shiftEnd ?: return false
            return e.totalMinutes < s.totalMinutes
        }
}

/**
 * Records when the user actually tapped Start / End shift today.
 * Stored separately from WorkScheduleConfig so frequent writes don't
 * touch the heavier config blob.
 */
@Serializable
data class ShiftSession(
    val actualStartMillis: Long? = null,
    val actualEndMillis: Long? = null
)

/**
 * An event scheduled relative to shift end rather than a fixed clock time.
 * offsetMinutes = 0 means "right when the shift ends"; 30 = 30 min after.
 */
@Serializable
data class AfterWorkEvent(
    val id: String,
    val title: String,
    val offsetMinutes: Int = 0
)

@Serializable
data class WorkScheduleConfig(
    // ISO day-of-week: 1 = Monday … 7 = Sunday
    val weekdayDefaults: Map<Int, DaySchedule> = mapOf(
        1 to DaySchedule(true, ShiftTime(9, 0), ShiftTime(17, 0)),
        2 to DaySchedule(true, ShiftTime(9, 0), ShiftTime(17, 0)),
        3 to DaySchedule(true, ShiftTime(9, 0), ShiftTime(17, 0)),
        4 to DaySchedule(true, ShiftTime(9, 0), ShiftTime(17, 0)),
        5 to DaySchedule(true, ShiftTime(9, 0), ShiftTime(17, 0)),
        6 to DaySchedule(false),
        7 to DaySchedule(false)
    ),
    // "yyyy-MM-dd" → DaySchedule; date overrides win over weekday defaults
    val dateOverrides: Map<String, DaySchedule> = emptyMap(),
    // Events scheduled relative to shift end; apply to all work days
    val afterWorkEvents: List<AfterWorkEvent> = emptyList()
)

// ── Interface ────────────────────────────────────────────────────────────────

interface WorkScheduleSignals {
    // Reactive config — emits whenever the schedule is written
    val configFlow: StateFlow<WorkScheduleConfig>

    // Schedule queries
    fun isWorkDay(): Boolean
    fun isWorkDay(date: Calendar): Boolean
    fun getTodaySchedule(): DaySchedule
    fun getSchedule(date: Calendar): DaySchedule
    fun isOnShiftNow(): Boolean
    fun getConfig(): WorkScheduleConfig
    fun dateKey(date: Calendar): String

    // Shift session — actual start/end tracked by the user today
    fun getTodaySession(): ShiftSession
    fun isShiftActive(): Boolean
    suspend fun startShift(startMillis: Long = System.currentTimeMillis())
    suspend fun endShift(endMillis: Long = System.currentTimeMillis())
    suspend fun resetTodaySession()

    /**
     * Scheduled time (epoch millis) for an after-work event.
     * Uses actualEndMillis when available; falls back to planned shiftEnd.
     * Returns null when neither is known.
     * The `~` prefix convention (estimated vs confirmed) is handled in the UI.
     */
    fun getScheduledTime(event: AfterWorkEvent): Long?

    // After-work events
    suspend fun addAfterWorkEvent(event: AfterWorkEvent)
    suspend fun removeAfterWorkEvent(id: String)

    // Schedule setters
    suspend fun setWeekday(isoDay: Int, schedule: DaySchedule)
    suspend fun setDateOverride(dateKey: String, schedule: DaySchedule)
    suspend fun setBulkDateOverrides(overrides: Map<String, DaySchedule>)
    suspend fun removeDateOverride(dateKey: String)
    suspend fun resetToDefaults()
}

// ── Implementation ───────────────────────────────────────────────────────────

class RealWorkScheduleSignals(context: Context) : WorkScheduleSignals {
    private val prefs by lazy {
        context.getSharedPreferences("waypoint_work_schedule_v2", Context.MODE_PRIVATE)
    }
    private val json = Json { ignoreUnknownKeys = true }

    // ── Config ───────────────────────────────────────────────────────────

    override fun getConfig(): WorkScheduleConfig {
        val raw = prefs.getString("config", null) ?: return WorkScheduleConfig()
        return try { json.decodeFromString(raw) } catch (_: Exception) { WorkScheduleConfig() }
    }

    private val _configFlow = MutableStateFlow(getConfig())
    override val configFlow: StateFlow<WorkScheduleConfig> = _configFlow.asStateFlow()

    private fun saveConfig(config: WorkScheduleConfig) {
        prefs.edit().putString("config", json.encodeToString(config)).apply()
        _configFlow.value = config
    }

    override fun isWorkDay(): Boolean = getTodaySchedule().isWork
    override fun isWorkDay(date: Calendar): Boolean = getSchedule(date).isWork

    override fun getTodaySchedule(): DaySchedule = getSchedule(Calendar.getInstance())

    override fun getSchedule(date: Calendar): DaySchedule {
        val config = getConfig()
        config.dateOverrides[dateKey(date)]?.let { return it }
        val isoDay = calendarDayToIso(date.get(Calendar.DAY_OF_WEEK))
        return config.weekdayDefaults[isoDay] ?: DaySchedule(isoDay in 1..5)
    }

    override fun isOnShiftNow(): Boolean {
        val schedule = getTodaySchedule()
        if (!schedule.isWork) return false
        val start = schedule.shiftStart ?: return true
        val end = schedule.shiftEnd ?: return true
        val now = Calendar.getInstance()
        val nowMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (!schedule.crossesMidnight) {
            nowMins in start.totalMinutes..end.totalMinutes
        } else {
            nowMins >= start.totalMinutes || nowMins <= end.totalMinutes
        }
    }

    override fun dateKey(date: Calendar): String =
        "%04d-%02d-%02d".format(
            date.get(Calendar.YEAR),
            date.get(Calendar.MONTH) + 1,
            date.get(Calendar.DAY_OF_MONTH)
        )

    override suspend fun setWeekday(isoDay: Int, schedule: DaySchedule) = withContext(Dispatchers.IO) {
        val c = getConfig()
        saveConfig(c.copy(weekdayDefaults = c.weekdayDefaults + (isoDay to schedule)))
    }

    override suspend fun setDateOverride(dateKey: String, schedule: DaySchedule) = withContext(Dispatchers.IO) {
        val c = getConfig()
        saveConfig(c.copy(dateOverrides = c.dateOverrides + (dateKey to schedule)))
    }

    override suspend fun setBulkDateOverrides(overrides: Map<String, DaySchedule>) = withContext(Dispatchers.IO) {
        if (overrides.isEmpty()) return@withContext
        val c = getConfig()
        saveConfig(c.copy(dateOverrides = c.dateOverrides + overrides))
    }

    override suspend fun removeDateOverride(dateKey: String) = withContext(Dispatchers.IO) {
        val c = getConfig()
        saveConfig(c.copy(dateOverrides = c.dateOverrides - dateKey))
    }

    // ── Shift session ─────────────────────────────────────────────────────

    private fun sessionKey(): String = "session_${dateKey(Calendar.getInstance())}"

    override fun getTodaySession(): ShiftSession {
        val raw = prefs.getString(sessionKey(), null) ?: return ShiftSession()
        return try { json.decodeFromString(raw) } catch (_: Exception) { ShiftSession() }
    }

    override fun isShiftActive(): Boolean {
        val s = getTodaySession()
        return s.actualStartMillis != null && s.actualEndMillis == null
    }

    override suspend fun startShift(startMillis: Long) = withContext(Dispatchers.IO) {
        val session = ShiftSession(actualStartMillis = startMillis)
        prefs.edit().putString(sessionKey(), json.encodeToString(session)).apply()
    }

    override suspend fun endShift(endMillis: Long) = withContext(Dispatchers.IO) {
        val session = getTodaySession().copy(actualEndMillis = endMillis)
        prefs.edit().putString(sessionKey(), json.encodeToString(session)).apply()
    }

    override suspend fun resetTodaySession() = withContext(Dispatchers.IO) {
        prefs.edit().remove(sessionKey()).apply()
    }

    // ── After-work events ─────────────────────────────────────────────────

    override fun getScheduledTime(event: AfterWorkEvent): Long? {
        val session = getTodaySession()

        // Actual end time takes priority over planned end
        if (session.actualEndMillis != null) {
            return session.actualEndMillis + event.offsetMinutes * 60_000L
        }

        // Fall back to the planned shift end for today
        val schedule = getTodaySchedule()
        val end = schedule.shiftEnd ?: return null
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, end.hour)
            set(Calendar.MINUTE, end.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (schedule.crossesMidnight) add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis + event.offsetMinutes * 60_000L
    }

    override suspend fun addAfterWorkEvent(event: AfterWorkEvent) = withContext(Dispatchers.IO) {
        val c = getConfig()
        saveConfig(c.copy(afterWorkEvents = c.afterWorkEvents + event))
    }

    override suspend fun removeAfterWorkEvent(id: String) = withContext(Dispatchers.IO) {
        val c = getConfig()
        saveConfig(c.copy(afterWorkEvents = c.afterWorkEvents.filter { it.id != id }))
    }

    override suspend fun resetToDefaults() = withContext(Dispatchers.IO) {
        saveConfig(WorkScheduleConfig())
    }
}

// ── Utility ──────────────────────────────────────────────────────────────────

fun calendarDayToIso(calDay: Int): Int = when (calDay) {
    Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
    Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6
    else -> 7  // SUNDAY
}
