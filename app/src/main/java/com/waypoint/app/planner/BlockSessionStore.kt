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
        sessionFlow.value = loadFromPrefs()?.takeIf { it.date == LocalDate.now().toString() }
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

    fun extendSession(extraMs: Long) {
        val current = loadFromPrefs() ?: return
        val updated = current.copy(scheduledEndMs = current.scheduledEndMs + extraMs)
        prefs.edit().putString("active", json.encodeToString(updated)).apply()
        sessionFlow.value = updated
        BlockNotificationHelper.postSessionLiveNotification(appContext, updated)
        AppLogger.i(TAG, "extendSession: +${extraMs / 60_000L}m → ends at ${updated.scheduledEndMs}")
    }

    fun loadCurrent(): ActiveBlockSession? {
        val session = loadFromPrefs() ?: return null
        if (session.date != LocalDate.now().toString()) {
            endSession()
            return null
        }
        return session
    }

    private fun loadFromPrefs(): ActiveBlockSession? =
        prefs.getString("active", null)?.let {
            try { json.decodeFromString<ActiveBlockSession>(it) } catch (_: Exception) { null }
        }

    private companion object {
        const val TAG = "BlockSessionStore"
    }
}
