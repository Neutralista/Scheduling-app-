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
    val date: String,  // "yyyy-MM-dd"
    /** Phase id → when Next phase started it; empty while it's left to the plan. */
    val phaseStarts: Map<String, Long> = emptyMap()
)

class BlockSessionStore(context: Context, logStore: BlockSessionLogStore? = null) {

    private val appContext: Context = context.applicationContext
    // Always logs: an instance made in a receiver used to have no log store, so a session that
    // timed out there was ended without ever reaching History.
    private val logStore: BlockSessionLogStore = logStore ?: BlockSessionLogStore(appContext)
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("wp_block_session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // Not loadCurrent(): at app start this is built before [taskCounter] is wired, and a session
    // that timed out would be logged with no task counts. The app calls loadCurrent() once it is.
    val sessionFlow = MutableStateFlow<ActiveBlockSession?>(loadFromPrefs()?.takeIf { !isExpired(it) })

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val session = loadFromPrefs()
        if (session != null && isExpired(session)) expire(session) else sessionFlow.value = session
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun startSession(
        block: NamedBlock,
        scheduledEndMs: Long,
        date: LocalDate,
        startedAtMs: Long = System.currentTimeMillis()
    ) {
        val session = ActiveBlockSession(
            blockId = block.id,
            blockName = block.name,
            colorArgb = block.colorArgb,
            startedAtMs = startedAtMs,
            scheduledEndMs = scheduledEndMs,
            date = date.toString()
        )
        prefs.edit().putString("active", json.encodeToString(session)).apply()
        sessionFlow.value = session
        BlockNotificationHelper.postSessionLiveNotification(appContext, session)
        AppLogger.i(TAG, "startSession: blockId=${block.id} name=${block.name} startedAtMs=$startedAtMs")
    }

    fun endSession(
        tasksCompleted: Int = 0,
        tasksTotal: Int = 0,
        taskMeasurements: List<BlockTaskMeasurement> = emptyList(),
        endedAtMs: Long = System.currentTimeMillis()
    ) {
        val current = loadFromPrefs()
        prefs.edit().remove("active").apply()
        sessionFlow.value = null
        BlockNotificationHelper.cancelSessionLiveNotification(appContext)
        if (current != null) {
            val endedAt = endedAtMs
            val phaseTimings = runCatching {
                val store = NamedBlockStore(appContext)
                store.loadBlock(current.blockId)?.takeIf { it.phases.isNotEmpty() }?.let { block ->
                    sessionPhaseTimings(
                        block, store.resolveActiveTasks(block.id, LocalDate.parse(current.date)),
                        current.startedAtMs, endedAt, current.phaseStarts
                    )
                }
            }.getOrNull().orEmpty()
            logStore.addEntry(BlockSessionLog(
                blockId = current.blockId,
                blockName = current.blockName,
                colorArgb = current.colorArgb,
                date = current.date,
                startedAtMs = current.startedAtMs,
                endedAtMs = endedAtMs,
                tasksCompleted = tasksCompleted,
                tasksTotal = tasksTotal,
                taskMeasurements = taskMeasurements,
                phaseTimings = phaseTimings
            ))
        }
        AppLogger.i(TAG, "endSession: measurements=${taskMeasurements.size}")
    }

    /** Ends the running session if it's [blockId]'s, counting its ticked-off tasks. */
    fun endIfBlock(blockId: String) {
        val session = loadFromPrefs()?.takeIf { it.blockId == blockId } ?: return
        val (done, total) = taskCounter?.let { count -> runCatching { count(session) }.getOrNull() } ?: (0 to 0)
        endSession(tasksCompleted = done, tasksTotal = total)
    }

    fun updateColor(colorArgb: Int?) {
        val current = loadFromPrefs() ?: return
        val updated = current.copy(colorArgb = colorArgb)
        prefs.edit().putString("active", json.encodeToString(updated)).apply()
        sessionFlow.value = updated
    }

    /** Next phase: marks [phaseId] as started now. */
    fun startPhase(phaseId: String, atMs: Long = System.currentTimeMillis()) {
        val current = loadFromPrefs() ?: return
        val updated = current.copy(phaseStarts = current.phaseStarts + (phaseId to atMs))
        prefs.edit().putString("active", json.encodeToString(updated)).apply()
        sessionFlow.value = updated
        AppLogger.i(TAG, "startPhase: $phaseId")
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
            expire(session)
            return null
        }
        return session
    }

    /**
     * Ends a session whose scheduled end passed without anyone ending it (the app was closed, or
     * nobody tapped Exit). Logged as ending at its scheduled end, not whenever this noticed —
     * the next launch could be hours later — with the block's tasks ticked off by then.
     */
    private fun expire(session: ActiveBlockSession) {
        val (done, total) = taskCounter?.let { count -> runCatching { count(session) }.getOrNull() } ?: (0 to 0)
        endSession(tasksCompleted = done, tasksTotal = total, endedAtMs = session.scheduledEndMs)
        AppLogger.i(TAG, "expire: blockId=${session.blockId} tasks=$done/$total")
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

    companion object {
        private const val TAG = "BlockSessionStore"

        /**
         * (ticked off, total) of a session's block tasks, for logging one that timed out. Set
         * once by the app when its task store is ready; process-wide, so instances made in
         * receivers use it too.
         */
        @Volatile
        var taskCounter: ((ActiveBlockSession) -> Pair<Int, Int>)? = null
    }
}
