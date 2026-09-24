package com.waypoint.app.alarm

import android.content.Context
import android.os.UserManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A copy of what's needed to re-arm alarms, kept in device-protected storage.
 *
 * After a restart, Android keeps an app's normal storage unreadable until the phone is first
 * unlocked, and alarms don't survive a restart. So a phone that restarted overnight (a system
 * update while charging, a crash) sat at the PIN screen with no alarms armed, and the wake
 * alarm never rang. This copy is readable before unlock: [LockedBootReceiver] re-arms from it
 * straight after the restart, and the ring path reads from it while the phone is still locked.
 * Once the phone is unlocked, BootReceiver re-arms everything from the real stores as before,
 * replacing these (same PendingIntents), and applies [takeFiredOneShots].
 */
object BootAlarmMirror {

    private const val PREFS = "wp_boot_alarms"
    private const val KEY_USER_ALARMS = "user_alarms"
    private const val KEY_WAKE = "wake"
    private const val KEY_FIRED_ONE_SHOTS = "fired_one_shots"
    private val json = Json { ignoreUnknownKeys = true }

    /** The sleep wake alarms as last scheduled; mirrors WakeAlarmScheduler's arguments. */
    @Serializable
    data class Wake(
        val wakeMs: Long,
        val count: Int,
        val intervalMinutes: Int,
        val gentleEnabled: Boolean,
        val mediumEnabled: Boolean,
        val wakeEnabled: Boolean
    )

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isUserUnlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java).isUserUnlocked

    fun saveUserAlarms(context: Context, alarms: List<AlarmEntry>) {
        prefs(context).edit().putString(KEY_USER_ALARMS, json.encodeToString(alarms)).apply()
    }

    fun userAlarms(context: Context): List<AlarmEntry> =
        prefs(context).getString(KEY_USER_ALARMS, null)
            ?.let { runCatching { json.decodeFromString<List<AlarmEntry>>(it) }.getOrNull() }
            .orEmpty()

    fun saveWake(context: Context, wake: Wake?) {
        prefs(context).edit().apply {
            if (wake == null) remove(KEY_WAKE) else putString(KEY_WAKE, json.encodeToString(wake))
        }.apply()
    }

    fun wake(context: Context): Wake? =
        prefs(context).getString(KEY_WAKE, null)
            ?.let { runCatching { json.decodeFromString<Wake>(it) }.getOrNull() }

    /**
     * A one-shot alarm that rang before unlock can't be switched off in the real store yet, so
     * it's switched off in this copy and remembered here; BootReceiver switches it off for real
     * after unlock. Without that, the next re-arm would ring it again the next day.
     */
    fun markOneShotFired(context: Context, alarmId: String) {
        val p = prefs(context)
        val fired = p.getStringSet(KEY_FIRED_ONE_SHOTS, emptySet()).orEmpty() + alarmId
        val alarms = userAlarms(context).map { if (it.id == alarmId) it.copy(enabled = false) else it }
        p.edit()
            .putStringSet(KEY_FIRED_ONE_SHOTS, fired)
            .putString(KEY_USER_ALARMS, json.encodeToString(alarms))
            .commit()
    }

    fun takeFiredOneShots(context: Context): Set<String> {
        val p = prefs(context)
        val fired = p.getStringSet(KEY_FIRED_ONE_SHOTS, emptySet()).orEmpty()
        if (fired.isNotEmpty()) p.edit().remove(KEY_FIRED_ONE_SHOTS).commit()
        return fired
    }
}
