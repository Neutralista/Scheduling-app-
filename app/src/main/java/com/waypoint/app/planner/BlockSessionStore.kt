package com.waypoint.app.planner

import android.content.Context
import android.content.SharedPreferences
import com.waypoint.app.AppLogger
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

class BlockSessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wp_block_session", Context.MODE_PRIVATE)
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
        AppLogger.i(TAG, "startSession: blockId=${block.id} name=${block.name}")
    }

    fun endSession() {
        prefs.edit().remove("active").apply()
        sessionFlow.value = null
        AppLogger.i(TAG, "endSession")
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
