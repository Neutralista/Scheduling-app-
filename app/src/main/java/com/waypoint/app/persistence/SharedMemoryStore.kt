package com.waypoint.app.persistence

import android.content.Context

/** Shared key-value store any script can read/write across sessions. */
class SharedMemoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("waypoint_shared_memory", Context.MODE_PRIVATE)

    fun get(key: String): String? = prefs.getString(key, null)

    fun set(key: String, value: String) { prefs.edit().putString(key, value).apply() }

    fun delete(key: String) { prefs.edit().remove(key).apply() }

    fun keys(): Set<String> = prefs.all.keys.toSet()
}
