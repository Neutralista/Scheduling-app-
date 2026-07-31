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
        return try {
            val saved = store.add(alarm)
            android.util.Log.d(TAG, "add: stored ${saved.id} ${saved.displayTime}")
            try {
                UserAlarmScheduler.schedule(context, saved)
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "add: schedule threw ${e.javaClass.name}: ${e.message}", e)
            }
            saved
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "add: store.add threw ${e.javaClass.name}: ${e.message}", e)
            throw e
        }
    }

    override suspend fun update(alarm: AlarmEntry) {
        try {
            store.update(alarm)
            android.util.Log.d(TAG, "update: stored ${alarm.id} ${alarm.displayTime}")
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "update: store.update threw ${e.javaClass.name}: ${e.message}", e)
            throw e
        }
        try {
            UserAlarmScheduler.schedule(context, alarm)
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "update: schedule threw ${e.javaClass.name}: ${e.message}", e)
        }
    }

    override suspend fun delete(id: String) {
        try {
            val alarm = store.loadAll().firstOrNull { it.id == id }
            if (alarm != null) {
                try {
                    UserAlarmScheduler.cancel(context, alarm)
                } catch (e: Throwable) {
                    android.util.Log.e(TAG, "delete: cancel threw ${e.javaClass.name}: ${e.message}", e)
                }
            }
            store.delete(id)
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "delete: threw ${e.javaClass.name}: ${e.message}", e)
            throw e
        }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        try {
            store.setEnabled(id, enabled)
            val alarm = store.loadAll().firstOrNull { it.id == id } ?: return
            try {
                if (enabled) UserAlarmScheduler.schedule(context, alarm)
                else UserAlarmScheduler.cancel(context, alarm)
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "setEnabled: scheduler threw ${e.javaClass.name}: ${e.message}", e)
            }
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "setEnabled: threw ${e.javaClass.name}: ${e.message}", e)
            throw e
        }
    }

    companion object { private const val TAG = "RealAlarmSignals" }

    override fun getAll(): List<AlarmEntry> = store.loadAll()
}
