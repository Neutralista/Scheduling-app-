package com.waypoint.app.signal

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.waypoint.app.AppLogger
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
    val calendarColor: Int,
    val eventId: Long = -1L
)

interface CalendarSignals {
    fun hasPermission(): Boolean
    fun hasWritePermission(): Boolean
    suspend fun todayEvents(): List<CalendarEvent>
    suspend fun eventsForDate(date: LocalDate): List<CalendarEvent>
    val cachedEvents: List<CalendarEvent>
    suspend fun refreshCache()
    /** Creates an event in the primary calendar. Returns the new event ID, or -1 on failure. */
    suspend fun createEvent(title: String, startMillis: Long, endMillis: Long, description: String = "", allDay: Boolean = false): Long
    /** Deletes an event by ID. Returns true if deleted. */
    suspend fun deleteEvent(eventId: Long): Boolean
    /** Returns all events written by Waypoint (description = "Logged by Waypoint") within the last [lookbackDays] days, as (eventId, event) pairs. */
    suspend fun queryWaypointEvents(lookbackDays: Int = 30): List<Pair<Long, CalendarEvent>>
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

    override suspend fun todayEvents(): List<CalendarEvent> = eventsForDate(LocalDate.now())

    override suspend fun eventsForDate(date: LocalDate): List<CalendarEvent> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()

        val zone = ZoneId.systemDefault()
        val startMillis = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMillis.toString())
            .appendPath(endMillis.toString())
            .build()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
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
            val eventIdIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val titleIdx   = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val beginIdx   = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIdx     = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDayIdx  = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val colorIdx   = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_COLOR)
            while (cursor.moveToNext()) {
                events.add(CalendarEvent(
                    title = cursor.getString(titleIdx) ?: "(no title)",
                    startMillis = cursor.getLong(beginIdx),
                    endMillis   = cursor.getLong(endIdx),
                    allDay      = cursor.getInt(allDayIdx) == 1,
                    calendarColor = cursor.getInt(colorIdx),
                    eventId     = cursor.getLong(eventIdIdx)
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
        if (!hasWritePermission()) {
            AppLogger.w(TAG, "createEvent: no WRITE_CALENDAR permission")
            return@withContext -1L
        }
        val calId = primaryCalendarId() ?: run {
            AppLogger.w(TAG, "createEvent: no suitable calendar found")
            return@withContext -1L
        }
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
            ?: run {
                AppLogger.w(TAG, "createEvent: insert returned null")
                return@withContext -1L
            }
        val eventId = uri.lastPathSegment?.toLongOrNull() ?: -1L
        AppLogger.i(TAG, "createEvent: created '$title' calId=$calId eventId=$eventId")
        eventId
    }

    override suspend fun deleteEvent(eventId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!hasWritePermission()) return@withContext false
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        context.contentResolver.delete(uri, null, null) > 0
    }

    override suspend fun queryWaypointEvents(lookbackDays: Int): List<Pair<Long, CalendarEvent>> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val cutoffMs = System.currentTimeMillis() - lookbackDays * 86_400_000L
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND
        )
        val selection = "${CalendarContract.Events.DESCRIPTION} = ? AND ${CalendarContract.Events.DTSTART} > ? AND ${CalendarContract.Events.DELETED} = 0"
        val result = mutableListOf<Pair<Long, CalendarEvent>>()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            arrayOf("Logged by Waypoint", cutoffMs.toString()),
            "${CalendarContract.Events.DTSTART} DESC"
        )?.use { cursor ->
            val idIdx    = cursor.getColumnIndexOrThrow(CalendarContract.Events._ID)
            val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
            val startIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
            val endIdx   = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
            while (cursor.moveToNext()) {
                result.add(cursor.getLong(idIdx) to CalendarEvent(
                    title = cursor.getString(titleIdx) ?: "Work shift",
                    startMillis = cursor.getLong(startIdx),
                    endMillis = cursor.getLong(endIdx),
                    allDay = false,
                    calendarColor = 0
                ))
            }
        }
        result
    }

    /**
     * Returns the best calendar to write to:
     * 1. Primary visible calendar (IS_PRIMARY = 1, VISIBLE = 1)
     * 2. Any visible calendar
     * 3. Any calendar (fallback for devices that don't set VISIBLE correctly)
     */
    private fun primaryCalendarId(): Long? {
        if (!hasPermission()) return null
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.VISIBLE
        )
        // Collect all calendars and log them so we can diagnose missing-calendar issues
        val all = mutableListOf<Triple<Long, Int, Int>>() // id, isPrimary, visible
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC"
        )?.use { cursor ->
            val idIdx      = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val primIdx    = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
            val visibleIdx = cursor.getColumnIndex(CalendarContract.Calendars.VISIBLE)
            while (cursor.moveToNext()) {
                val id      = cursor.getLong(idIdx)
                val isPrim  = if (primIdx >= 0) cursor.getInt(primIdx) else 0
                val visible = if (visibleIdx >= 0) cursor.getInt(visibleIdx) else 1
                all += Triple(id, isPrim, visible)
            }
        }
        AppLogger.i(TAG, "primaryCalendarId: found ${all.size} calendars: $all")
        // Prefer primary+visible, then any visible, then any
        return all.firstOrNull { it.second == 1 && it.third == 1 }?.first
            ?: all.firstOrNull { it.third == 1 }?.first
            ?: all.firstOrNull()?.first
    }

    companion object {
        private const val TAG = "CalendarSignals"
    }
}
