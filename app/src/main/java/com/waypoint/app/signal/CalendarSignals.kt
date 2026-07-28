package com.waypoint.app.signal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

data class CalendarEvent(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val calendarColor: Int
)

interface CalendarSignals {
    fun hasPermission(): Boolean
    suspend fun todayEvents(): List<CalendarEvent>
}

class RealCalendarSignals(private val context: Context) : CalendarSignals {

    override fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED

    override suspend fun todayEvents(): List<CalendarEvent> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val startMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMillis.toString())
            .appendPath(endMillis.toString())
            .build()

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_COLOR
        )

        val events = mutableListOf<CalendarEvent>()
        context.contentResolver.query(
            uri, projection, null, null,
            CalendarContract.Instances.BEGIN + " ASC"
        )?.use { cursor ->
            val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIdx   = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDayIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val colorIdx  = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_COLOR)
            while (cursor.moveToNext()) {
                events.add(CalendarEvent(
                    title = cursor.getString(titleIdx) ?: "(no title)",
                    startMillis = cursor.getLong(beginIdx),
                    endMillis   = cursor.getLong(endIdx),
                    allDay      = cursor.getInt(allDayIdx) == 1,
                    calendarColor = cursor.getInt(colorIdx)
                ))
            }
        }
        events
    }
}
