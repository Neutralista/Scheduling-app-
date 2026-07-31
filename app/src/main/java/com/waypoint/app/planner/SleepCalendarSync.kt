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
     */
    suspend fun write(
        context: Context,
        logStore: SleepLogStore,
        bedMs: Long,
        wakeMs: Long,
        oldEventId: Long?
    ) {
        AppLogger.i(TAG, "write: bedMs=$bedMs wakeMs=$wakeMs oldEventId=$oldEventId")
        val cal = RealCalendarSignals(context)
        if (!cal.hasWritePermission()) {
            AppLogger.w(TAG, "write: no WRITE_CALENDAR permission, skipping")
            return
        }
        if (oldEventId != null) cal.deleteEvent(oldEventId)
        val eventId = cal.createEvent(
            title = "Sleep",
            startMillis = bedMs,
            endMillis = wakeMs,
            description = "Logged by Waypoint"
        )
        AppLogger.i(TAG, "write: createEvent returned eventId=$eventId")
        if (eventId > 0) logStore.updateCalendarEventId(eventId)
    }

    suspend fun delete(context: Context, eventId: Long) {
        AppLogger.i(TAG, "delete: eventId=$eventId")
        RealCalendarSignals(context).deleteEvent(eventId)
    }
}
