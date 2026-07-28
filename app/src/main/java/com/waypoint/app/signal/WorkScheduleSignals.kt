package com.waypoint.app.signal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar

@Serializable
data class WorkScheduleConfig(
    // ISO day-of-week: 1 = Monday … 7 = Sunday
    val weekdayDefaults: Map<Int, Boolean> = mapOf(
        1 to true, 2 to true, 3 to true, 4 to true, 5 to true,
        6 to false, 7 to false
    ),
    // "yyyy-MM-dd" → isWork; date overrides win over weekday defaults
    val dateOverrides: Map<String, Boolean> = emptyMap()
)

interface WorkScheduleSignals {
    /** Whether today is configured as a work day. */
    fun isWorkDay(): Boolean
    /** Whether the given Calendar instant's date is a work day. */
    fun isWorkDay(date: Calendar): Boolean
    /** Full config — used by WorkScheduleWidget to render toggles. */
    fun getConfig(): WorkScheduleConfig
    /** Set the default for a whole weekday. isoDay: 1 = Mon … 7 = Sun. */
    suspend fun setWeekday(isoDay: Int, isWork: Boolean)
    /** Override a specific date. dateKey format: "yyyy-MM-dd". */
    suspend fun setDateOverride(dateKey: String, isWork: Boolean)
    /** Remove a date override, reverting to the weekday default. */
    suspend fun removeDateOverride(dateKey: String)
}

class RealWorkScheduleSignals(context: Context) : WorkScheduleSignals {
    private val prefs by lazy {
        context.getSharedPreferences("waypoint_work_schedule", Context.MODE_PRIVATE)
    }
    private val json = Json { ignoreUnknownKeys = true }

    override fun getConfig(): WorkScheduleConfig {
        val raw = prefs.getString("config", null) ?: return WorkScheduleConfig()
        return try { json.decodeFromString(raw) } catch (_: Exception) { WorkScheduleConfig() }
    }

    override fun isWorkDay(): Boolean = isWorkDay(Calendar.getInstance())

    override fun isWorkDay(date: Calendar): Boolean {
        val config = getConfig()
        config.dateOverrides[dateKey(date)]?.let { return it }
        val isoDay = calendarDayToIso(date.get(Calendar.DAY_OF_WEEK))
        return config.weekdayDefaults[isoDay] ?: (isoDay in 1..5)
    }

    override suspend fun setWeekday(isoDay: Int, isWork: Boolean) = withContext(Dispatchers.IO) {
        val updated = getConfig().let { it.copy(weekdayDefaults = it.weekdayDefaults + (isoDay to isWork)) }
        prefs.edit().putString("config", json.encodeToString(updated)).apply()
    }

    override suspend fun setDateOverride(dateKey: String, isWork: Boolean) = withContext(Dispatchers.IO) {
        val updated = getConfig().let { it.copy(dateOverrides = it.dateOverrides + (dateKey to isWork)) }
        prefs.edit().putString("config", json.encodeToString(updated)).apply()
    }

    override suspend fun removeDateOverride(dateKey: String) = withContext(Dispatchers.IO) {
        val updated = getConfig().let { it.copy(dateOverrides = it.dateOverrides - dateKey) }
        prefs.edit().putString("config", json.encodeToString(updated)).apply()
    }

    private fun dateKey(date: Calendar): String =
        "%04d-%02d-%02d".format(
            date.get(Calendar.YEAR),
            date.get(Calendar.MONTH) + 1,
            date.get(Calendar.DAY_OF_MONTH)
        )
}

fun calendarDayToIso(calDay: Int): Int = when (calDay) {
    Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
    Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6
    else -> 7  // SUNDAY
}
