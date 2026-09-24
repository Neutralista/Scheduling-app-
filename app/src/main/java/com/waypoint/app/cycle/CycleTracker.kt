package com.waypoint.app.cycle

import android.content.Context
import android.os.PowerManager
import com.waypoint.app.AppLogger
import com.waypoint.app.planner.SleepCalendarSync
import com.waypoint.app.planner.SleepLogEntry
import com.waypoint.app.planner.SleepLogStore
import com.waypoint.app.planner.SleepModeState
import com.waypoint.app.planner.WakeConfirmation
import com.waypoint.app.signal.RealCalendarSignals
import java.util.UUID

class CycleTracker(private val context: Context) {

    val store = CycleStore(context)

    private val prefs = context.getSharedPreferences("waypoint_cycle_tracker", Context.MODE_PRIVATE)
    private val sleepLogStore = SleepLogStore(context)

    /**
     * Called every time a new cycle opens — both automatic and manual.
     * Wire this up (e.g. in Application.onCreate) to reset per-cycle state such as
     * task completion marks.
     */
    var onNewCycle: (() -> Unit)? = null

    companion object {
        private const val TAG = "CycleTracker"
        private const val KEY_LAST_ACTIVE = "last_active_ms"

        // Min gap since last wake before we consider this a genuinely new cycle.
        // Prevents re-opening a cycle on brief re-unlocks during the same awake period.
        private const val MIN_NEW_CYCLE_GAP_MS = 4 * 3600_000L  // 4 hours

        // Continuous inactivity threshold for sleep onset detection.
        private const val SLEEP_INACTIVITY_MS = 60 * 60_000L    // 60 minutes

        // Fallback threshold used when sleep was never detected at all (sleep mode never armed,
        // or its periodic inactivity check never ran). Deliberately much longer than
        // SLEEP_INACTIVITY_MS — a plain gap in opening the app during a normal day (a few hours
        // of not checking Waypoint while awake) should never look like sleep on its own. Combined
        // with also requiring the gap to span NIGHT_MARKER_HOUR, this stays conservative.
        private const val FALLBACK_INACTIVITY_MS = 3 * 3600_000L  // 3 hours

        // A gap containing this local hour covers a night. Unlike "crossed a calendar date", this
        // still works when the last activity was after midnight.
        private const val NIGHT_MARKER_HOUR = 4

        // Beyond this, a sleep tracker still reporting SLEEPING is stuck (e.g. its checks
        // stopped), not asleep — stop deferring to it.
        private const val MAX_TRACKED_SLEEP_MS = 16 * 3600_000L
    }

    /**
     * Call when the user is confirmed active: Waypoint foregrounded, an alarm dismissed, or the
     * sleep tracker confirming a wake. Phone unlocks are NOT a signal — manifest receivers don't
     * get ACTION_USER_PRESENT on Android 8+, so "last active" means last seen by one of these.
     * Opens a new cycle or closes the previous sleeping cycle and opens a fresh one.
     *
     * Runs from receivers and onResume(); an uncaught exception here would crash the app on
     * every open, which looks exactly like "the app doesn't come back". Swallow and log
     * instead of ever propagating.
     *
     * [explicitWake] marks dismissing the wake alarm — a deliberate wake-up that closes the
     * cycle without waiting for the sleep tracker, even if no sleep was detected.
     */
    fun recordActive(explicitWake: Boolean = false) {
        try {
            recordActiveInternal(explicitWake)
        } catch (e: Throwable) {
            AppLogger.e(TAG, "recordActive threw — leaving cycle state as-is", e)
        }
    }

    private fun recordActiveInternal(explicitWake: Boolean) {
        val now = System.currentTimeMillis()
        val current = store.loadCurrent()

        when {
            current == null -> {
                AppLogger.i(TAG, "recordActive: no open cycle — opening first")
                openCycle(now)
            }
            current.sleepStartMillis != null -> {
                val sleepStart = current.sleepStartMillis
                val gap = now - sleepStart
                val trackerSleeping = sleepLogStore.getSleepModeState() == SleepModeState.SLEEPING &&
                    gap < MAX_TRACKED_SLEEP_MS
                // Let the sleep tracker see this activity as a tentative wake; it confirms and
                // logs the night itself (see WakeConfirmation).
                if (trackerSleeping) sleepLogStore.noteActivity(now)
                val isMorningWake = explicitWake || WakeConfirmation.isConfirmed(
                    sleepLogStore.getSleepStartMillis() ?: sleepStart, now, now, sleepLogStore.getScheduledWakeMs()
                )
                val loggedWake = sleepLogStore.loggedWakeAfter(sleepStart, now)
                when {
                    trackerSleeping && !isMorningWake -> {
                        // Night-time phone check: keep the sleep onset. Clearing it here is what
                        // used to leave the cycle unable to close the next morning.
                        AppLogger.i(TAG, "recordActive: tentative night wake — keeping cycle ${current.id} sleeping")
                    }
                    loggedWake != null -> {
                        AppLogger.i(TAG, "recordActive: sleep tracker logged wake at $loggedWake — closing cycle ${current.id}")
                        store.save(current.copy(nextWakeMillis = loggedWake))
                        openCycle(loggedWake)
                    }
                    trackerSleeping -> {
                        AppLogger.i(TAG, "recordActive: morning wake while tracker sleeping — closing cycle ${current.id}")
                        store.save(current.copy(nextWakeMillis = now))
                        openCycle(now)
                    }
                    gap >= MIN_NEW_CYCLE_GAP_MS -> {
                        AppLogger.i(TAG, "recordActive: closing cycle ${current.id} after ${gap / 3600_000}h sleep gap")
                        store.save(current.copy(nextWakeMillis = now))
                        openCycle(now)
                    }
                    else -> {
                        AppLogger.i(TAG, "recordActive: gap ${gap / 60_000}min < threshold — treating as same cycle, clearing sleep")
                        store.save(current.copy(sleepStartMillis = null))
                    }
                }
            }
            else -> {
                // No sleep onset was recorded. Two independent ways to still catch this as a
                // genuinely new day:
                //  1. Sleep mode was armed and the phone was inactive long enough — the original,
                //     more sensitive signal (sleep mode being on already implies the user expected
                //     sleep detection to matter here).
                //  2. No sleep mode at all, but a much longer inactivity gap (FALLBACK_INACTIVITY_MS)
                //     that also spans NIGHT_MARKER_HOUR — e.g. sleep detection never ran because
                //     sleep mode was never armed, but the gap clearly covered a night. Requiring
                //     the night hour (not just duration) keeps a single long daytime gap in opening
                //     the app from ever looking like sleep.
                //  3. The wake alarm was dismissed on a cycle that has run long enough — sleep just
                //     went undetected.
                val openMs = now - current.wakeMillis
                val lastActive = prefs.getLong(KEY_LAST_ACTIVE, now)
                val inactiveDuration = now - lastActive
                val sleepModeActive = sleepLogStore.getSleepModeState() != SleepModeState.IDLE
                val spannedNight = Cycle.nextAtTime(lastActive, NIGHT_MARKER_HOUR, 0) <= now

                val closeViaSleepMode = openMs >= MIN_NEW_CYCLE_GAP_MS &&
                    inactiveDuration >= SLEEP_INACTIVITY_MS && sleepModeActive
                val closeViaUndetectedGap = openMs >= MIN_NEW_CYCLE_GAP_MS &&
                    inactiveDuration >= FALLBACK_INACTIVITY_MS && spannedNight
                val closeViaWakeAlarm = explicitWake && openMs >= MIN_NEW_CYCLE_GAP_MS

                if (closeViaSleepMode || closeViaUndetectedGap || closeViaWakeAlarm) {
                    AppLogger.i(
                        TAG,
                        "recordActive: no sleep start, cycle open ${openMs / 3600_000}h, " +
                            "inactive ${inactiveDuration / 60_000}min, sleepMode=$sleepModeActive, " +
                            "spannedNight=$spannedNight, wakeAlarm=$explicitWake — closing as fallback"
                    )
                    store.save(current.copy(nextWakeMillis = now))
                    openCycle(now)
                } else {
                    AppLogger.i(TAG, "recordActive: continuing cycle ${current.id} (open ${openMs / 60_000}min, inactive ${inactiveDuration / 60_000}min, sleepMode=$sleepModeActive, spannedNight=$spannedNight)")
                }
            }
        }

        updateLastActive(now)
        // Only keep the periodic check alive when sleep mode is active.
        if (sleepLogStore.getSleepModeState() != SleepModeState.IDLE) {
            WakeCheckReceiver.scheduleCheck(context)
        }
    }

    /**
     * Called by the periodic WakeCheckReceiver alarm.
     * Checks phone inactivity and marks sleep onset if threshold is exceeded.
     */
    fun checkInactivity() {
        try {
            checkInactivityInternal()
        } catch (e: Throwable) {
            AppLogger.e(TAG, "checkInactivity threw — leaving cycle state as-is", e)
        }
    }

    private fun checkInactivityInternal() {
        val now = System.currentTimeMillis()
        val current = store.loadCurrent() ?: return
        if (current.sleepStartMillis != null) return  // already sleeping

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            updateLastActive(now)
            WakeCheckReceiver.scheduleCheck(context)
            return
        }

        val lastActive = prefs.getLong(KEY_LAST_ACTIVE, now)
        val inactiveDuration = now - lastActive
        AppLogger.i(TAG, "checkInactivity: inactive=${inactiveDuration / 60_000}min (threshold=${SLEEP_INACTIVITY_MS / 60_000}min)")

        if (inactiveDuration >= SLEEP_INACTIVITY_MS) {
            // Estimate onset slightly after the phone went dark
            val onset = lastActive + (SLEEP_INACTIVITY_MS / 4)
            AppLogger.i(TAG, "checkInactivity: sleep onset → cycle ${current.id} at $onset")
            store.save(current.copy(sleepStartMillis = onset))
        } else {
            WakeCheckReceiver.scheduleCheck(context)
        }
    }

    private fun updateLastActive(millis: Long) {
        prefs.edit().putLong(KEY_LAST_ACTIVE, millis).apply()
    }

    /**
     * Called by the sleep scheduler when it authoritatively detects sleep onset.
     * Preferred over the independent inactivity check because the sleep scheduler
     * already has a calibrated estimate of the onset time.
     */
    fun recordSleepAt(sleepStartMs: Long) {
        try {
            val current = store.loadCurrent() ?: return
            if (current.sleepStartMillis != null) return  // already recorded
            AppLogger.i(TAG, "recordSleepAt: cycle=${current.id} sleepStart=$sleepStartMs (from sleep scheduler)")
            store.save(current.copy(sleepStartMillis = sleepStartMs))
        } catch (e: Throwable) {
            AppLogger.e(TAG, "recordSleepAt threw — leaving cycle state as-is", e)
        }
    }

    /** A sleep log entry changed by [saveCycle] whose calendar event still needs rewriting. */
    data class SleepCalendarChange(val entry: SleepLogEntry?, val staleEventIds: List<Long>)

    /**
     * Save a manually-edited cycle. Handles:
     * - Sleep sync: mirrors an edited night into SleepLogStore (see [syncSleepEntry]); returns the
     *   calendar follow-up for [applySleepCalendarChange], or null if there is none.
     * - Edit propagation: when nextWakeMillis changes, updates the successor cycle's wakeMillis;
     *   when wakeMillis changes, updates the predecessor cycle's nextWakeMillis.
     * - Auto-start: if an open cycle is being closed, opens a new cycle at nextWakeMillis.
     */
    fun saveCycle(original: Cycle, updated: Cycle): SleepCalendarChange? {
        store.save(updated)

        val calendarChange = syncSleepEntry(original, updated)

        val all = store.loadAll()

        // Propagate nextWakeMillis change → successor's wakeMillis
        val oldEnd = original.nextWakeMillis
        val newEnd = updated.nextWakeMillis
        if (newEnd != null && newEnd != oldEnd && oldEnd != null) {
            all.firstOrNull { it.id != updated.id && it.wakeMillis == oldEnd }
                ?.let { store.save(it.copy(wakeMillis = newEnd)) }
        }

        // Propagate wakeMillis change → predecessor's nextWakeMillis
        if (updated.wakeMillis != original.wakeMillis) {
            all.firstOrNull { it.id != updated.id && it.nextWakeMillis == original.wakeMillis }
                ?.let { store.save(it.copy(nextWakeMillis = updated.wakeMillis)) }
        }

        // Auto-open successor when closing an open cycle (if none already exists)
        if (original.isOpen && !updated.isOpen && newEnd != null && store.loadCurrent() == null) {
            openCycle(newEnd)
        }
        return calendarChange
    }

    /**
     * Mirrors a cycle's edited night into SleepLogStore. Entries are keyed by wake date, the same
     * way the sleep tracker logs them, and only an entry for this same night is ever replaced or
     * removed — another night's entry is never overwritten. Does nothing if the sleep times
     * didn't change.
     */
    private fun syncSleepEntry(original: Cycle, updated: Cycle): SleepCalendarChange? {
        val oldBed = original.sleepStartMillis
        val oldWake = original.nextWakeMillis
        val newBed = updated.sleepStartMillis
        val newWake = updated.nextWakeMillis
        if (oldBed == newBed && oldWake == newWake) return null

        val oldEntry = if (oldBed != null && oldWake != null) {
            sleepLogStore.loadForDate(Cycle.dateLabel(oldWake))
                ?.takeIf { overlaps(it.bedMillis, it.wakeMillis, oldBed, oldWake) }
        } else null

        if (newBed == null || newWake == null || newWake <= newBed) {
            if (oldEntry == null) return null
            sleepLogStore.deleteEntry(oldEntry.dateIso)
            return SleepCalendarChange(entry = null, staleEventIds = listOfNotNull(oldEntry.calendarEventId))
        }

        val newDate = Cycle.dateLabel(newWake)
        val occupant = sleepLogStore.loadForDate(newDate)?.takeIf { it.dateIso != oldEntry?.dateIso }
        if (occupant != null && !overlaps(occupant.bedMillis, occupant.wakeMillis, newBed, newWake)) {
            AppLogger.w(TAG, "saveCycle: $newDate already holds a different night — leaving the sleep log as-is")
            return null
        }
        if (oldEntry != null && oldEntry.dateIso != newDate) sleepLogStore.deleteEntry(oldEntry.dateIso)

        val staleEventIds = listOfNotNull(oldEntry?.calendarEventId, occupant?.calendarEventId)
        // Keep the old event linked until a replacement exists, so a failed calendar write
        // doesn't make the background calendar sync create a duplicate.
        val entry = SleepLogEntry(newDate, newBed, newWake, staleEventIds.firstOrNull())
        sleepLogStore.saveEntry(entry)
        return SleepCalendarChange(entry, staleEventIds)
    }

    private fun overlaps(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean =
        aStart < bEnd && aEnd > bStart

    /** Replaces the calendar event(s) for a night changed by [saveCycle]. */
    suspend fun applySleepCalendarChange(change: SleepCalendarChange) {
        try {
            val calendar = RealCalendarSignals(context)
            if (!calendar.hasWritePermission()) return
            val entry = change.entry
            if (entry != null) {
                val newId = SleepCalendarSync.writeForEntry(context, entry.bedMillis, entry.wakeMillis, null)
                if (newId <= 0) return
                val stillCurrent = sleepLogStore.loadForDate(entry.dateIso)
                    ?.takeIf { it.bedMillis == entry.bedMillis && it.wakeMillis == entry.wakeMillis }
                if (stillCurrent == null) {
                    // Edited again while this was in flight — the newer edit owns the event.
                    calendar.deleteEvent(newId)
                    return
                }
                sleepLogStore.saveEntry(stillCurrent.copy(calendarEventId = newId))
            }
            change.staleEventIds.forEach { calendar.deleteEvent(it) }
        } catch (e: Throwable) {
            AppLogger.e(TAG, "applySleepCalendarChange threw", e)
        }
    }

    /** Manually open a new cycle right now — used when the user taps "Start cycle". */
    fun manualStart(): Cycle {
        val now = System.currentTimeMillis()
        // Close any open cycle first
        store.loadCurrent()?.let { store.save(it.copy(nextWakeMillis = now)) }
        return openCycle(now)
    }

    private fun openCycle(wakeMillis: Long): Cycle {
        val cycle = Cycle(
            id = UUID.randomUUID().toString(),
            wakeMillis = wakeMillis,
            dateLabel = Cycle.dateLabel(wakeMillis)
        )
        store.save(cycle)
        AppLogger.i(TAG, "openCycle: id=${cycle.id} date=${cycle.dateLabel}")
        onNewCycle?.invoke()
        return cycle
    }
}
