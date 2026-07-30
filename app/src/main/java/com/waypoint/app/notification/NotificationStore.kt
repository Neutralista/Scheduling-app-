package com.waypoint.app.notification

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

object NotificationStore {

    private const val PREFS = "script_notifications"

    fun slot(id: String): Int = abs(id.hashCode()) % 1000
    fun alarmRequestCode(slot: Int): Int = 10000 + slot
    fun actionRequestCode(slot: Int, i: Int): Int = 11000 + slot * 8 + i
    fun notifId(slot: Int): Int = 1000 + slot

    fun save(context: Context, config: NotificationConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("cfg_${config.id}", configToJson(config).toString()).apply()
    }

    fun load(context: Context, id: String): NotificationConfig? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("cfg_$id", null) ?: return null
        return runCatching { jsonToConfig(JSONObject(raw)) }.getOrNull()
    }

    fun remove(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove("cfg_$id").apply()
    }
}

private fun configToJson(config: NotificationConfig): JSONObject {
    val o = JSONObject()
    o.put("id",    config.id)
    o.put("title", config.title)
    o.put("body",  config.body)
    val arr = JSONArray()
    config.actions.forEach { a ->
        arr.put(JSONObject().also { ao ->
            ao.put("label",         a.label)
            ao.put("behavior",      a.behavior)
            ao.put("snoozeMinutes", a.snoozeMinutes)
            ao.put("tab",           a.tab)
            ao.put("scriptId",      a.scriptId)
        })
    }
    o.put("actions", arr)
    config.weekly?.let { w ->
        o.put("weekly", JSONObject().also { wo ->
            wo.put("day",    w.day)
            wo.put("hour",   w.hour)
            wo.put("minute", w.minute)
        })
    }
    return o
}

private fun jsonToConfig(o: JSONObject): NotificationConfig {
    val actions = mutableListOf<ActionConfig>()
    val arr = o.optJSONArray("actions")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val ao = arr.getJSONObject(i)
            actions += ActionConfig(
                label         = ao.optString("label", ""),
                behavior      = ao.optString("behavior", "dismiss"),
                snoozeMinutes = ao.optInt("snoozeMinutes", 60),
                tab           = ao.optString("tab", "widgets"),
                scriptId      = ao.optString("scriptId", "")
            )
        }
    }
    val weekly = o.optJSONObject("weekly")?.let { w ->
        WeeklyTrigger(
            day    = w.optInt("day",    1),
            hour   = w.optInt("hour",   16),
            minute = w.optInt("minute", 0)
        )
    }
    return NotificationConfig(
        id      = o.getString("id"),
        title   = o.optString("title", ""),
        body    = o.optString("body",  ""),
        actions = actions,
        weekly  = weekly
    )
}
