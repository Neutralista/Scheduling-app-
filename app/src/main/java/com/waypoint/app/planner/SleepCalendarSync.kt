package com.waypoint.app.planner

import android.content.Context
import com.waypoint.app.signal.RealCalendarSignals

object SleepCalendarSync {

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
        val cal = RealCalendarSignals(context)
        if (!cal.hasWritePermission()) return
        if (oldEventId != null) cal.deleteEvent(oldEventId)
        val eventId = cal.createEvent(
            title = "Sleep",
            startMillis = bedMs,
            endMillis = wakeMs,
            description = "Logged by Waypoint"
        )
        if (eventId > 0) logStore.updateCalendarEventId(eventId)
    }

    suspend fun delete(context: Context, eventId: Long) {
        RealCalendarSignals(context).deleteEvent(eventId)
    }
}
