package com.waypoint.app.planner

import android.content.Context
import com.waypoint.app.AppLogger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists user-defined BufferRules in SharedPreferences.
 * Each rule is stored independently by id for easy add/remove.
 */
class BufferRulesStore(context: Context) {

    private val prefs = context.getSharedPreferences("waypoint_buffer_rules", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "BufferRulesStore"
    }

    fun save(rule: BufferRule) {
        prefs.edit().putString(rule.id, json.encodeToString(rule)).apply()
        AppLogger.i(TAG, "save: id=${rule.id} anchor=${rule.anchorType} dur=${rule.durationMinutes}m")
    }

    fun delete(id: String) {
        prefs.edit().remove(id).apply()
        AppLogger.i(TAG, "delete: id=$id")
    }

    fun load(id: String): BufferRule? {
        val raw = prefs.getString(id, null) ?: return null
        return try { json.decodeFromString<BufferRule>(raw) } catch (_: Exception) { null }
    }

    fun loadAll(): List<BufferRule> = prefs.all.values.mapNotNull { raw ->
        try { json.decodeFromString<BufferRule>(raw as? String ?: return@mapNotNull null) }
        catch (_: Exception) { null }
    }

    fun loadEnabled(): List<BufferRule> = loadAll().filter { it.enabled }

    fun setEnabled(id: String, enabled: Boolean) {
        val rule = load(id) ?: return
        save(rule.copy(enabled = enabled))
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * Registers all enabled buffer rules into the planner as fixed events anchored to
     * the work shift or sleep schedule. Call after shift/sleep times are known.
     *
     * @param registry     the planner registry to write into
     * @param shiftStartMs shift start epoch ms (null if no shift today)
     * @param shiftEndMs   shift end epoch ms (null if no shift today)
     * @param sleepStartMs bed time epoch ms (null if unknown)
     * @param wakeMs       wake time epoch ms (null if unknown)
     */
    fun syncToRegistry(
        registry: EventPlannerRegistry,
        shiftStartMs: Long? = null,
        shiftEndMs: Long? = null,
        sleepStartMs: Long? = null,
        wakeMs: Long? = null
    ) {
        registry.unregisterByWidget("buffer_rules")
        loadEnabled().forEach { rule ->
            val anchor = when (rule.anchorType) {
                AnchorType.BEFORE_SHIFT_START -> shiftStartMs
                AnchorType.AFTER_SHIFT_END    -> shiftEndMs
                AnchorType.BEFORE_SLEEP       -> sleepStartMs
                AnchorType.AFTER_WAKE         -> wakeMs
            } ?: return@forEach

            val durationMs = rule.durationMinutes * 60_000L
            val offsetMs   = rule.offsetMinutes * 60_000L

            val (start, end) = when (rule.anchorType) {
                AnchorType.BEFORE_SHIFT_START,
                AnchorType.BEFORE_SLEEP -> {
                    val end   = anchor - offsetMs
                    val start = end - durationMs
                    start to end
                }
                AnchorType.AFTER_SHIFT_END,
                AnchorType.AFTER_WAKE -> {
                    val start = anchor + offsetMs
                    val end   = start + durationMs
                    start to end
                }
            }

            registry.register(
                PlannerEvent(
                    id              = "buffer_${rule.id}",
                    title           = rule.label,
                    durationMinutes = rule.durationMinutes,
                    priority        = PlannerPriority.SLEEP - 1,
                    category        = EventCategory.BUFFER,
                    fixedStartMillis = start,
                    fixedEndMillis   = end,
                    sourceWidgetId  = "buffer_rules"
                )
            )
        }
        AppLogger.i(TAG, "syncToRegistry: ${loadEnabled().size} buffer rules")
    }
}
