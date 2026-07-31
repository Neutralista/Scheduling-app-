package com.waypoint.app.alarm

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

interface AlarmSignals {
    val alarmsFlow: StateFlow<List<AlarmEntry>>
    suspend fun add(alarm: AlarmEntry): AlarmEntry
    suspend fun update(alarm: AlarmEntry)
    suspend fun delete(id: String)
    suspend fun setEnabled(id: String, enabled: Boolean)
    fun getAll(): List<AlarmEntry>
}

class RealAlarmSignals(private val context: Context, private val store: AlarmStore) : AlarmSignals {

    override val alarmsFlow: StateFlow<List<AlarmEntry>> get() = store.flow

    override suspend fun add(alarm: AlarmEntry): AlarmEntry {
        val saved = store.add(alarm)
        UserAlarmScheduler.schedule(context, saved)
        return saved
    }

    override suspend fun update(alarm: AlarmEntry) {
        store.update(alarm)
        UserAlarmScheduler.schedule(context, alarm)
    }

    override suspend fun delete(id: String) {
        val alarm = store.loadAll().firstOrNull { it.id == id }
        if (alarm != null) UserAlarmScheduler.cancel(context, alarm)
        store.delete(id)
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        store.setEnabled(id, enabled)
        val alarm = store.loadAll().firstOrNull { it.id == id } ?: return
        if (enabled) UserAlarmScheduler.schedule(context, alarm)
        else UserAlarmScheduler.cancel(context, alarm)
    }

    override fun getAll(): List<AlarmEntry> = store.loadAll()
}
