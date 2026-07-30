package com.waypoint.app.signal

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

data class CalendarEvent(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val calendarColor: Int
)

interface CalendarSignals {
    fun hasPermission(): Boolean
    fun hasWritePermission(): Boolean
    suspend fun todayEvents(): List<CalendarEvent>
    val cachedEvents: List<CalendarEvent>
    suspend fun refreshCache()
    /** Creates an event in the primary calendar. Returns the new event ID, or -1 on failure. */
    suspend fun createEvent(title: String, startMillis: Long, endMillis: Long, description: String = "", allDay: Boolean = false): Long
    /** Deletes an event by ID. Returns true if deleted. */
    suspend fun deleteEvent(eventId: Long): Boolean
}

class RealCalendarSignals(private val context: Context) : CalendarSignals {

    @Volatile override var cachedEvents: List<CalendarEvent> = emptyList()
        private set

    override suspend fun refreshCache() { cachedEvents = todayEvents() }

    override fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED

    override fun hasWritePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) ==
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

    override suspend fun createEvent(
        title: String,
        startMillis: Long,
        endMillis: Long,
        description: String,
        allDay: Boolean
    ): Long = withContext(Dispatchers.IO) {
        if (!hasWritePermission()) return@withContext -1L
        val calId = primaryCalendarId() ?: return@withContext -1L
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (description.isNotEmpty()) put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return@withContext -1L
        uri.lastPathSegment?.toLongOrNull() ?: -1L
    }

    override suspend fun deleteEvent(eventId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!hasWritePermission()) return@withContext false
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        context.contentResolver.delete(uri, null, null) > 0
    }

    private fun primaryCalendarId(): Long? {
        if (!hasPermission()) return null
        val projection = arrayOf(CalendarContract.Calendars._ID)
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }
}
