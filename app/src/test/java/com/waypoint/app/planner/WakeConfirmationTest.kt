package com.waypoint.app.planner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeConfirmationTest {

    private val hour = 3600_000L
    private val minute = 60_000L

    // Night: asleep at 23:00 (t=0), scheduled wake at 07:00.
    private val sleepStart = 0L
    private val scheduledWake = 8 * hour

    @Test
    fun nightTimeCheck_isNotConfirmed() {
        val candidate = 3 * hour // 02:00
        assertFalse(WakeConfirmation.isConfirmed(sleepStart, candidate, candidate, scheduledWake))
    }

    @Test
    fun activityInMorningWindow_isConfirmedImmediately() {
        val candidate = scheduledWake - 60 * minute // 06:00
        assertTrue(WakeConfirmation.isConfirmed(sleepStart, candidate, candidate, scheduledWake))
    }

    @Test
    fun activityJustBeforeMorningWindow_isNotConfirmed() {
        val candidate = scheduledWake - WakeConfirmation.MORNING_WINDOW_MS - minute
        assertFalse(WakeConfirmation.isConfirmed(sleepStart, candidate, candidate, scheduledWake))
    }

    @Test
    fun sustainedNightTimeActivity_isConfirmed() {
        val candidate = 3 * hour
        val now = candidate + WakeConfirmation.SUSTAINED_WAKE_MS
        assertTrue(WakeConfirmation.isConfirmed(sleepStart, candidate, now, scheduledWake))
    }

    @Test
    fun withoutSchedule_shortNightIsNotConfirmed() {
        val candidate = 3 * hour
        assertFalse(WakeConfirmation.isConfirmed(sleepStart, candidate, candidate, null))
    }

    @Test
    fun withoutSchedule_fullNightIsConfirmed() {
        val candidate = WakeConfirmation.FULL_NIGHT_MS
        assertTrue(WakeConfirmation.isConfirmed(sleepStart, candidate, candidate, null))
    }

    @Test
    fun scheduleFromAnotherNight_fallsBackToDuration() {
        // Wake time before sleep onset (e.g. slept after a night shift) is not this night's schedule.
        val staleWake = sleepStart - hour
        assertFalse(WakeConfirmation.isConfirmed(sleepStart, 3 * hour, 3 * hour, staleWake))
        assertTrue(WakeConfirmation.isConfirmed(sleepStart, 7 * hour, 7 * hour, staleWake))
    }

    @Test
    fun backToSleep_afterIdleThreshold() {
        val lastInteractive = 3 * hour
        assertFalse(WakeConfirmation.wentBackToSleep(lastInteractive, lastInteractive + 20 * minute))
        assertTrue(WakeConfirmation.wentBackToSleep(lastInteractive, lastInteractive + WakeConfirmation.BACK_TO_SLEEP_MS))
    }
}
