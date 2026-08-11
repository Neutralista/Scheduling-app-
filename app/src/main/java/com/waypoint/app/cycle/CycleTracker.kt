package com.waypoint.app.cycle

import android.content.Context
import android.os.PowerManager
import com.waypoint.app.AppLogger
import com.waypoint.app.planner.SleepLogEntry
import com.waypoint.app.planner.SleepLogStore
import com.waypoint.app.planner.SleepModeState
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
    }

    /**
     * Call when the user is confirmed active (screen unlocked, app foregrounded).
     * Opens a new cycle or closes the previous sleeping cycle and opens a fresh one.
     */
    fun recordActive() {
        val now = System.currentTimeMillis()
        val current = store.loadCurrent()

        when {
            current == null -> {
                AppLogger.i(TAG, "recordActive: no open cycle — opening first")
                openCycle(now)
            }
            current.sleepStartMillis != null -> {
                // Was sleeping: if enough time passed, this is a genuine new wake
                val gap = now - (current.sleepStartMillis)
                if (gap >= MIN_NEW_CYCLE_GAP_MS) {
                    AppLogger.i(TAG, "recordActive: closing cycle ${current.id} after ${gap / 3600_000}h sleep gap")
                    store.save(current.copy(nextWakeMillis = now))
                    openCycle(now)
                } else {
                    AppLogger.i(TAG, "recordActive: gap ${gap / 60_000}min < threshold — treating as same cycle, clearing sleep")
                    store.save(current.copy(sleepStartMillis = null))
                }
            }
            else -> {
                // No sleep onset was recorded. Only apply the fallback close when sleep mode
                // is active AND the phone has been inactive long enough to suggest sleep
                // occurred (detection alarm was probably missed). Without both guards this
                // fires during normal waking hours and fragments the day into 4-hour chunks.
                val openMs = now - current.wakeMillis
                val lastActive = prefs.getLong(KEY_LAST_ACTIVE, now)
                val inactiveDuration = now - lastActive
                val sleepModeActive = sleepLogStore.getSleepModeState() != SleepModeState.IDLE
                if (openMs >= MIN_NEW_CYCLE_GAP_MS && inactiveDuration >= SLEEP_INACTIVITY_MS && sleepModeActive) {
                    AppLogger.i(TAG, "recordActive: no sleep start, cycle open ${openMs / 3600_000}h, inactive ${inactiveDuration / 60_000}min — closing as fallback")
                    store.save(current.copy(nextWakeMillis = now))
                    openCycle(now)
                } else {
                    AppLogger.i(TAG, "recordActive: continuing cycle ${current.id} (open ${openMs / 60_000}min, inactive ${inactiveDuration / 60_000}min, sleepMode=$sleepModeActive)")
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

    fun updateLastActive(millis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_ACTIVE, millis).apply()
    }

    /**
     * Called by the sleep scheduler when it authoritatively detects sleep onset.
     * Preferred over the independent inactivity check because the sleep scheduler
     * already has a calibrated estimate of the onset time.
     */
    fun recordSleepAt(sleepStartMs: Long) {
        val current = store.loadCurrent() ?: return
        if (current.sleepStartMillis != null) return  // already recorded
        AppLogger.i(TAG, "recordSleepAt: cycle=${current.id} sleepStart=$sleepStartMs (from sleep scheduler)")
        store.save(current.copy(sleepStartMillis = sleepStartMs))
    }

    /**
     * Save a manually-edited cycle. Handles:
     * - Sleep sync: writes a SleepLogEntry when both sleep endpoints are present.
     * - Edit propagation: when nextWakeMillis changes, updates the successor cycle's wakeMillis;
     *   when wakeMillis changes, updates the predecessor cycle's nextWakeMillis.
     * - Auto-start: if an open cycle is being closed, opens a new cycle at nextWakeMillis.
     */
    fun saveCycle(original: Cycle, updated: Cycle) {
        store.save(updated)

        // Sync sleep → SleepLogStore
        if (updated.sleepStartMillis != null && updated.nextWakeMillis != null) {
            sleepLogStore.saveEntry(
                SleepLogEntry(
                    dateIso  = Cycle.dateLabel(updated.sleepStartMillis),
                    bedMillis  = updated.sleepStartMillis,
                    wakeMillis = updated.nextWakeMillis
                )
            )
        }

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
