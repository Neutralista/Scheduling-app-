package com.waypoint.app.signal

import android.content.Context
import kotlinx.coroutines.Dispatchers
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
    val dateOverrides: Map<String, DaySchedule> = emptyMap()
)

// ── Interface ────────────────────────────────────────────────────────────────

interface WorkScheduleSignals {
    fun isWorkDay(): Boolean
    fun isWorkDay(date: Calendar): Boolean
    fun getTodaySchedule(): DaySchedule
    fun getSchedule(date: Calendar): DaySchedule
    /** True when the current time falls within today's configured shift window. */
    fun isOnShiftNow(): Boolean
    fun getConfig(): WorkScheduleConfig
    fun dateKey(date: Calendar): String
    suspend fun setWeekday(isoDay: Int, schedule: DaySchedule)
    suspend fun setDateOverride(dateKey: String, schedule: DaySchedule)
    suspend fun removeDateOverride(dateKey: String)
}

// ── Implementation ───────────────────────────────────────────────────────────

class RealWorkScheduleSignals(context: Context) : WorkScheduleSignals {
    private val prefs by lazy {
        context.getSharedPreferences("waypoint_work_schedule_v2", Context.MODE_PRIVATE)
    }
    private val json = Json { ignoreUnknownKeys = true }

    override fun getConfig(): WorkScheduleConfig {
        val raw = prefs.getString("config", null) ?: return WorkScheduleConfig()
        return try { json.decodeFromString(raw) } catch (_: Exception) { WorkScheduleConfig() }
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
        val start = schedule.shiftStart ?: return true   // no shift = whole day
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
        val updated = getConfig().let { it.copy(weekdayDefaults = it.weekdayDefaults + (isoDay to schedule)) }
        prefs.edit().putString("config", json.encodeToString(updated)).apply()
    }

    override suspend fun setDateOverride(dateKey: String, schedule: DaySchedule) = withContext(Dispatchers.IO) {
        val updated = getConfig().let { it.copy(dateOverrides = it.dateOverrides + (dateKey to schedule)) }
        prefs.edit().putString("config", json.encodeToString(updated)).apply()
    }

    override suspend fun removeDateOverride(dateKey: String) = withContext(Dispatchers.IO) {
        val updated = getConfig().let { it.copy(dateOverrides = it.dateOverrides - dateKey) }
        prefs.edit().putString("config", json.encodeToString(updated)).apply()
    }
}

// ── Utility ──────────────────────────────────────────────────────────────────

fun calendarDayToIso(calDay: Int): Int = when (calDay) {
    Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
    Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6
    else -> 7  // SUNDAY
}
