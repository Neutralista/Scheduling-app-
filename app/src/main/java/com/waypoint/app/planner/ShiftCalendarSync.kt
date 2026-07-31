package com.waypoint.app.planner

import android.content.Context
import com.waypoint.app.AppLogger
import com.waypoint.app.signal.RealCalendarSignals
import com.waypoint.app.signal.WorkScheduleSignals

object ShiftCalendarSync {

    private const val TAG = "ShiftCalendarSync"

    /**
     * Creates a "Work shift" calendar event for the given start→end window.
     * If the session already has a [calendarEventId], the old event is deleted first.
     * The new event ID is persisted back via [ws.updateShiftCalendarEventId].
     */
    suspend fun write(
        context: Context,
        ws: WorkScheduleSignals,
        startMs: Long,
        endMs: Long
    ): Boolean {
        AppLogger.i(TAG, "write: startMs=$startMs endMs=$endMs")
        val cal = RealCalendarSignals(context)
        if (!cal.hasWritePermission()) {
            AppLogger.w(TAG, "write: no WRITE_CALENDAR permission, skipping")
            return false
        }
        val oldEventId = ws.getTodaySession().calendarEventId
        if (oldEventId != null) cal.deleteEvent(oldEventId)
        val eventId = cal.createEvent(
            title = "Work shift",
            startMillis = startMs,
            endMillis = endMs,
            description = "Logged by Waypoint"
        )
        AppLogger.i(TAG, "write: createEvent returned eventId=$eventId")
        if (eventId > 0) ws.updateShiftCalendarEventId(eventId)
        return eventId > 0
    }

    suspend fun delete(context: Context, eventId: Long) {
        AppLogger.i(TAG, "delete: eventId=$eventId")
        RealCalendarSignals(context).deleteEvent(eventId)
    }
}
