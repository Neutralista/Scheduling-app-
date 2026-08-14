package com.waypoint.app.planner

import android.content.Context
import com.waypoint.app.AppLogger
import com.waypoint.app.signal.RealCalendarSignals

object SleepCalendarSync {

    private const val TAG = "SleepCalendarSync"

    /**
     * Creates a "Sleep" calendar event for the given bed→wake window.
     * If [oldEventId] is provided, the old event is deleted first (edit case).
     * The new event ID is persisted back via [logStore.updateCalendarEventId].
     * Returns true if the event was written, false if WRITE_CALENDAR is not granted.
     */
    suspend fun write(
        context: Context,
        logStore: SleepLogStore,
        bedMs: Long,
        wakeMs: Long,
        oldEventId: Long?
    ): Boolean {
        AppLogger.i(TAG, "write: bedMs=$bedMs wakeMs=$wakeMs oldEventId=$oldEventId")
        val cal = RealCalendarSignals(context)
        if (!cal.hasWritePermission()) {
            AppLogger.w(TAG, "write: no WRITE_CALENDAR permission, skipping")
            return false
        }
        val eventId = cal.createEvent(
            title = "Sleep",
            startMillis = bedMs,
            endMillis = wakeMs,
            description = "Logged by Waypoint"
        )
        AppLogger.i(TAG, "write: createEvent returned eventId=$eventId")
        if (eventId > 0) {
            logStore.updateCalendarEventId(eventId)
            if (oldEventId != null) cal.deleteEvent(oldEventId)
        }
        return eventId > 0
    }

    /**
     * Creates (or replaces) a calendar event for any sleep entry, returning the new event id.
     * Unlike [write], this does not update the log store — the caller persists the id themselves.
     * Returns -1 if WRITE_CALENDAR is not granted or the create fails.
     */
    suspend fun writeForEntry(
        context: Context,
        bedMs: Long,
        wakeMs: Long,
        oldEventId: Long?
    ): Long {
        val cal = RealCalendarSignals(context)
        if (!cal.hasWritePermission()) {
            AppLogger.w(TAG, "writeForEntry: no WRITE_CALENDAR permission, skipping")
            return -1L
        }
        val eventId = cal.createEvent(
            title = "Sleep",
            startMillis = bedMs,
            endMillis = wakeMs,
            description = "Logged by Waypoint"
        )
        AppLogger.i(TAG, "writeForEntry: createEvent returned eventId=$eventId")
        if (eventId > 0 && oldEventId != null) cal.deleteEvent(oldEventId)
        return eventId
    }

    /**
     * Reconciles every [SleepLogStore] entry from the last 30 days with the system calendar.
     * For entries that have no linked calendar event (or whose event was deleted), a new event
     * is created and the entry is updated with the new id.
     * Safe to call from a background worker — does nothing when WRITE_CALENDAR is not granted.
     */
    suspend fun syncLogToCalendar(context: Context, logStore: SleepLogStore) {
        val cal = RealCalendarSignals(context)
        if (!cal.hasWritePermission()) {
            AppLogger.w(TAG, "syncLogToCalendar: no WRITE_CALENDAR permission")
            return
        }

        // Build the set of sleep event ids currently in the device calendar
        val existingIds = cal.queryWaypointEvents(30)
            .filter { (_, ev) -> ev.title == "Sleep" }
            .mapTo(mutableSetOf()) { it.first }

        val entries = logStore.loadRecent(30)
        AppLogger.i(TAG, "syncLogToCalendar: ${entries.size} log entries, ${existingIds.size} calendar events")

        for (entry in entries) {
            val linked = entry.calendarEventId
            if (linked != null && linked in existingIds) continue  // already in sync

            // No linked event or the event was deleted — create a fresh one
            val eventId = writeForEntry(context, entry.bedMillis, entry.wakeMillis, null)
            if (eventId > 0) {
                logStore.saveEntry(entry.copy(calendarEventId = eventId))
                AppLogger.i(TAG, "syncLogToCalendar: wrote event $eventId for ${entry.dateIso}")
            }
        }
    }

    suspend fun delete(context: Context, eventId: Long) {
        AppLogger.i(TAG, "delete: eventId=$eventId")
        RealCalendarSignals(context).deleteEvent(eventId)
    }
}
