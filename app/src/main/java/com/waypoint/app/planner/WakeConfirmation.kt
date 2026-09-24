package com.waypoint.app.planner

/**
 * Decides whether phone activity during detected sleep is the real wake-up or a brief
 * night-time check (clock glance, bathroom trip) the user sleeps through afterwards.
 *
 * The first activity is only a *candidate* wake. It is confirmed when it lands near the
 * scheduled wake time, when the night has already run long enough with no usable schedule, or
 * when the user is still active [SUSTAINED_WAKE_MS] later. If the phone instead goes idle for
 * [BACK_TO_SLEEP_MS], the candidate is dropped and the original sleep onset stands.
 */
object WakeConfirmation {
    const val MORNING_WINDOW_MS = 90 * 60_000L
    const val SUSTAINED_WAKE_MS = 20 * 60_000L
    const val BACK_TO_SLEEP_MS = 30 * 60_000L
    const val FULL_NIGHT_MS = 6 * 3600_000L

    // A scheduled wake further than this from sleep onset belongs to some other night.
    private const val MAX_PLAUSIBLE_NIGHT_MS = 18 * 3600_000L

    fun isConfirmed(sleepStartMs: Long, candidateMs: Long, nowMs: Long, scheduledWakeMs: Long?): Boolean {
        if (nowMs - candidateMs >= SUSTAINED_WAKE_MS) return true
        val wake = scheduledWakeMs?.takeIf { it > sleepStartMs && it - sleepStartMs <= MAX_PLAUSIBLE_NIGHT_MS }
        return if (wake != null) {
            candidateMs >= wake - MORNING_WINDOW_MS
        } else {
            candidateMs - sleepStartMs >= FULL_NIGHT_MS
        }
    }

    fun wentBackToSleep(lastInteractiveMs: Long, nowMs: Long): Boolean =
        nowMs - lastInteractiveMs >= BACK_TO_SLEEP_MS
}
