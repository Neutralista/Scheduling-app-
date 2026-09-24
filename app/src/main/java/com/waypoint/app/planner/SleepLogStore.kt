package com.waypoint.app.planner

import android.content.Context
import android.content.SharedPreferences
import com.waypoint.app.AppLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

@Serializable
data class SleepLogEntry(
    val dateIso: String,
    val bedMillis: Long,
    val wakeMillis: Long,
    val calendarEventId: Long? = null
)

enum class SleepModeState { IDLE, MONITORING, SLEEPING }

class SleepLogStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("waypoint_sleep_log", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val TAG                 = "SleepLogStore"
        const val KEY_STATE           = "sleep_mode_state"
        const val KEY_MODE_START      = "sleep_mode_start"
        const val KEY_LAST_ACTIVE     = "last_active"
        const val KEY_SLEEP_START     = "sleep_start"
        const val KEY_LAST_NUDGE      = "last_nudge"
        const val KEY_SCHED_BED       = "scheduled_bed_ms"
        const val KEY_SCHED_WAKE      = "scheduled_wake_ms"
        const val KEY_WAKE_CANDIDATE  = "wake_candidate"
        const val KEY_LAST_INTERACTIVE = "last_interactive_while_sleeping"
    }

    // ─── Sleep Mode State ──────────────────────────────────────────────────────

    fun getSleepModeState(): SleepModeState =
        try { SleepModeState.valueOf(prefs.getString(KEY_STATE, SleepModeState.IDLE.name)!!) }
        catch (_: Exception) { SleepModeState.IDLE }

    fun getSleepModeStartMillis(): Long? =
        prefs.getLong(KEY_MODE_START, -1L).takeIf { it >= 0 }

    fun getLastActiveMillis(): Long? =
        prefs.getLong(KEY_LAST_ACTIVE, -1L).takeIf { it >= 0 }

    fun getSleepStartMillis(): Long? =
        prefs.getLong(KEY_SLEEP_START, -1L).takeIf { it >= 0 }

    fun getLastNudgeMillis(): Long? =
        prefs.getLong(KEY_LAST_NUDGE, -1L).takeIf { it >= 0 }

    fun getScheduledBedMs(): Long? =
        prefs.getLong(KEY_SCHED_BED, -1L).takeIf { it >= 0 }

    fun getScheduledWakeMs(): Long? =
        prefs.getLong(KEY_SCHED_WAKE, -1L).takeIf { it >= 0 }

    fun updateScheduledTimes(bedMs: Long, wakeMs: Long) {
        prefs.edit().putLong(KEY_SCHED_BED, bedMs).putLong(KEY_SCHED_WAKE, wakeMs).apply()
    }

    fun enterSleepMode() {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_STATE, SleepModeState.MONITORING.name)
            .putLong(KEY_MODE_START, now)
            .putLong(KEY_LAST_ACTIVE, now)
            .remove(KEY_SLEEP_START)
            .remove(KEY_WAKE_CANDIDATE)
            .remove(KEY_LAST_INTERACTIVE)
            .apply()
    }

    /**
     * Called when the phone is known to be active (app opened, or 10-min check shows interactive).
     * While SLEEPING, the first activity is only a tentative wake (see [WakeConfirmation]) — a
     * brief night-time check must not end the night. Once confirmed, logs bed → first activity
     * and resets to IDLE.
     * Returns true if a sleep entry was written.
     */
    fun recordPhoneActive(): Boolean {
        val state = getSleepModeState()
        if (state == SleepModeState.IDLE) return false

        val now = System.currentTimeMillis()
        if (state == SleepModeState.MONITORING) {
            prefs.edit().putLong(KEY_LAST_ACTIVE, now).apply()
            return false
        }

        val sleepStart = getSleepStartMillis() ?: now
        val candidate = prefs.getLong(KEY_WAKE_CANDIDATE, -1L).takeIf { it >= 0 } ?: now
        prefs.edit()
            .putLong(KEY_LAST_ACTIVE, now)
            .putLong(KEY_WAKE_CANDIDATE, candidate)
            .putLong(KEY_LAST_INTERACTIVE, now)
            .apply()

        if (!WakeConfirmation.isConfirmed(sleepStart, candidate, now, getScheduledWakeMs())) {
            AppLogger.i(TAG, "recordPhoneActive: tentative wake since $candidate — not confirmed yet")
            return false
        }

        AppLogger.i(TAG, "recordPhoneActive: wake confirmed at $candidate → IDLE")
        if (candidate > sleepStart) logManual(sleepStart, candidate)
        prefs.edit()
            .putString(KEY_STATE, SleepModeState.IDLE.name)
            .remove(KEY_MODE_START)
            .remove(KEY_SLEEP_START)
            .remove(KEY_WAKE_CANDIDATE)
            .remove(KEY_LAST_INTERACTIVE)
            .apply()
        return true
    }

    /**
     * Called by SleepCheckReceiver every 10 min.
     * Returns true when state transitions to SLEEPING (sleep onset detected).
     */
    fun onCheckAlarm(isPhoneInteractive: Boolean): Boolean {
        val state = getSleepModeState()
        if (state == SleepModeState.IDLE) {
            AppLogger.i(TAG, "onCheckAlarm: IDLE, nothing to do")
            return false
        }

        val now = System.currentTimeMillis()

        if (isPhoneInteractive) {
            AppLogger.i(TAG, "onCheckAlarm: phone interactive, state=$state → recordPhoneActive")
            return recordPhoneActive()
        }

        // Phone not interactive
        if (state == SleepModeState.MONITORING) {
            val lastActive = getLastActiveMillis() ?: now
            val inactiveMs = now - lastActive
            AppLogger.i(TAG, "onCheckAlarm: MONITORING, inactive for ${inactiveMs / 60_000}min (need 60min)")
            if (inactiveMs >= 60 * 60_000L) {
                val sleepStartMs = lastActive + 10 * 60_000L
                AppLogger.i(TAG, "onCheckAlarm: sleep onset! sleepStart=$sleepStartMs → SLEEPING")
                prefs.edit()
                    .putString(KEY_STATE, SleepModeState.SLEEPING.name)
                    .putLong(KEY_SLEEP_START, sleepStartMs)
                    .apply()
                return true
            }
        } else {
            val lastInteractive = prefs.getLong(KEY_LAST_INTERACTIVE, -1L).takeIf { it >= 0 }
            if (lastInteractive != null && WakeConfirmation.wentBackToSleep(lastInteractive, now)) {
                AppLogger.i(TAG, "onCheckAlarm: SLEEPING, idle since $lastInteractive — back to sleep, dropping tentative wake")
                prefs.edit().remove(KEY_WAKE_CANDIDATE).remove(KEY_LAST_INTERACTIVE).apply()
            } else {
                AppLogger.i(TAG, "onCheckAlarm: SLEEPING, phone inactive (waiting for wake)")
            }
        }
        return false
    }

    fun cancelSleepMode() {
        prefs.edit()
            .putString(KEY_STATE, SleepModeState.IDLE.name)
            .remove(KEY_MODE_START)
            .remove(KEY_LAST_ACTIVE)
            .remove(KEY_SLEEP_START)
            .remove(KEY_WAKE_CANDIDATE)
            .remove(KEY_LAST_INTERACTIVE)
            .apply()
    }

    /**
     * Marks activity during SLEEPING as a tentative wake without confirming it. For callers that
     * don't handle the sleep-logged follow-up (calendar write); the next [recordPhoneActive]
     * confirms using this earlier wake time.
     */
    fun noteActivity(now: Long = System.currentTimeMillis()) {
        if (getSleepModeState() != SleepModeState.SLEEPING) return
        val edit = prefs.edit().putLong(KEY_LAST_ACTIVE, now).putLong(KEY_LAST_INTERACTIVE, now)
        if (!prefs.contains(KEY_WAKE_CANDIDATE)) edit.putLong(KEY_WAKE_CANDIDATE, now)
        edit.apply()
    }

    /** Most recent logged wake that ends a sleep starting at [sleepStartMs], if any. */
    fun loggedWakeAfter(sleepStartMs: Long, nowMs: Long): Long? =
        loadRecent(2).map { it.wakeMillis }.filter { it in (sleepStartMs + 1)..nowMs }.maxOrNull()

    fun updateLastNudge(millis: Long) {
        prefs.edit().putLong(KEY_LAST_NUDGE, millis).apply()
    }

    // ─── Log Entries ───────────────────────────────────────────────────────────

    fun loadToday(): SleepLogEntry? {
        val raw = prefs.getString("log_${LocalDate.now()}", null) ?: return null
        return try { json.decodeFromString(raw) } catch (_: Exception) { null }
    }

    fun loadForDate(dateIso: String): SleepLogEntry? {
        val raw = prefs.getString("log_$dateIso", null) ?: return null
        return try { json.decodeFromString(raw) } catch (_: Exception) { null }
    }

    fun loadRecent(days: Int = 7): List<SleepLogEntry> {
        val today = LocalDate.now()
        return (0 until days).mapNotNull { offset ->
            prefs.getString("log_${today.minusDays(offset.toLong())}", null)
                ?.let { try { json.decodeFromString<SleepLogEntry>(it) } catch (_: Exception) { null } }
        }
    }

    fun logManual(bedMillis: Long, wakeMillis: Long) {
        val today = LocalDate.now()
        val entry = SleepLogEntry(dateIso = today.toString(), bedMillis = bedMillis, wakeMillis = wakeMillis)
        prefs.edit().putString("log_$today", json.encodeToString(entry)).apply()
        pruneOldEntries()
    }

    /** Removes today's log entry and returns it (caller can use it to delete the calendar event). */
    fun clearToday(): SleepLogEntry? {
        val key = "log_${LocalDate.now()}"
        val raw = prefs.getString(key, null)
        val entry = raw?.let { try { json.decodeFromString<SleepLogEntry>(it) } catch (_: Exception) { null } }
        if (raw != null) prefs.edit().remove(key).apply()
        return entry
    }

    fun updateCalendarEventId(eventId: Long) {
        val key = "log_${LocalDate.now()}"
        val raw = prefs.getString(key, null) ?: return
        val entry = try { json.decodeFromString<SleepLogEntry>(raw) } catch (_: Exception) { return }
        prefs.edit().putString(key, json.encodeToString(entry.copy(calendarEventId = eventId))).apply()
    }

    fun saveEntry(entry: SleepLogEntry) {
        prefs.edit().putString("log_${entry.dateIso}", json.encodeToString(entry)).apply()
    }

    fun deleteEntry(dateIso: String): SleepLogEntry? {
        val key = "log_$dateIso"
        val raw = prefs.getString(key, null)
        val entry = raw?.let { try { json.decodeFromString<SleepLogEntry>(it) } catch (_: Exception) { null } }
        if (raw != null) prefs.edit().remove(key).apply()
        return entry
    }

    private fun pruneOldEntries() {
        val cutoff = LocalDate.now().minusDays(30)
        val toRemove = prefs.all.keys.filter { key ->
            if (!key.startsWith("log_")) return@filter false
            try { LocalDate.parse(key.removePrefix("log_")).isBefore(cutoff) }
            catch (_: Exception) { false }
        }
        if (toRemove.isNotEmpty()) prefs.edit().apply { toRemove.forEach { remove(it) } }.apply()
    }
}
