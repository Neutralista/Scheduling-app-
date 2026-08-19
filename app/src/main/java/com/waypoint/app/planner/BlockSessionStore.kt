package com.waypoint.app.planner

import android.content.Context
import android.content.SharedPreferences
import com.waypoint.app.AppLogger
import com.waypoint.app.notification.BlockNotificationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

@Serializable
data class ActiveBlockSession(
    val blockId: String,
    val blockName: String,
    val colorArgb: Int?,
    val startedAtMs: Long,
    val scheduledEndMs: Long,
    val date: String  // "yyyy-MM-dd"
)

class BlockSessionStore(context: Context, private val logStore: BlockSessionLogStore? = null) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("wp_block_session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    val sessionFlow = MutableStateFlow<ActiveBlockSession?>(loadCurrent())

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        sessionFlow.value = loadFromPrefs()?.takeIf { !isExpired(it) }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun startSession(block: NamedBlock, scheduledEndMs: Long, date: LocalDate) {
        val session = ActiveBlockSession(
            blockId = block.id,
            blockName = block.name,
            colorArgb = block.colorArgb,
            startedAtMs = System.currentTimeMillis(),
            scheduledEndMs = scheduledEndMs,
            date = date.toString()
        )
        prefs.edit().putString("active", json.encodeToString(session)).apply()
        sessionFlow.value = session
        BlockNotificationHelper.postSessionLiveNotification(appContext, session)
        AppLogger.i(TAG, "startSession: blockId=${block.id} name=${block.name}")
    }

    fun endSession(
        tasksCompleted: Int = 0,
        tasksTotal: Int = 0,
        taskMeasurements: List<BlockTaskMeasurement> = emptyList()
    ) {
        val current = loadFromPrefs()
        prefs.edit().remove("active").apply()
        sessionFlow.value = null
        BlockNotificationHelper.cancelSessionLiveNotification(appContext)
        if (current != null && logStore != null) {
            logStore.addEntry(BlockSessionLog(
                blockId = current.blockId,
                blockName = current.blockName,
                colorArgb = current.colorArgb,
                date = current.date,
                startedAtMs = current.startedAtMs,
                endedAtMs = System.currentTimeMillis(),
                tasksCompleted = tasksCompleted,
                tasksTotal = tasksTotal,
                taskMeasurements = taskMeasurements
            ))
        }
        AppLogger.i(TAG, "endSession: measurements=${taskMeasurements.size}")
    }

    fun updateColor(colorArgb: Int?) {
        val current = loadFromPrefs() ?: return
        val updated = current.copy(colorArgb = colorArgb)
        prefs.edit().putString("active", json.encodeToString(updated)).apply()
        sessionFlow.value = updated
    }

    fun extendSession(extraMs: Long) {
        val current = loadFromPrefs() ?: return
        val updated = current.copy(scheduledEndMs = current.scheduledEndMs + extraMs)
        prefs.edit().putString("active", json.encodeToString(updated)).apply()
        sessionFlow.value = updated
        BlockNotificationHelper.postSessionLiveNotification(appContext, updated)
        AppLogger.i(TAG, "extendSession: +${extraMs / 60_000L}m → ends at ${updated.scheduledEndMs}")
    }

    /**
     * Re-posts the live block-session countdown card if the user swiped it away but a session
     * is still active. Meant for a periodic background tick, not an instant reaction to the
     * dismiss — see UserAlarmScheduler.healStatusNotification for the same pattern.
     */
    fun healSessionNotification() {
        val session = loadCurrent() ?: return
        if (BlockNotificationHelper.isSessionLiveNotificationShowing(appContext)) return
        BlockNotificationHelper.postSessionLiveNotification(appContext, session)
    }

    fun loadCurrent(): ActiveBlockSession? {
        val session = loadFromPrefs() ?: return null
        if (isExpired(session)) {
            endSession()
            return null
        }
        return session
    }

    // A block session is stale once its scheduled end has passed — not merely once the
    // calendar date has rolled over, which a session that legitimately crosses midnight
    // (e.g. 23:00-00:30) would already have done while still genuinely in progress.
    private fun isExpired(session: ActiveBlockSession): Boolean =
        System.currentTimeMillis() > session.scheduledEndMs

    private fun loadFromPrefs(): ActiveBlockSession? =
        prefs.getString("active", null)?.let {
            try { json.decodeFromString<ActiveBlockSession>(it) } catch (_: Exception) { null }
        }

    private companion object {
        const val TAG = "BlockSessionStore"
    }
}
