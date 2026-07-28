package com.waypoint.app.signal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Duration

sealed class DeviceEvent {
    data object ScreenOn : DeviceEvent()
    data object ScreenOff : DeviceEvent()
    data class WokeAfterInactivity(val inactiveDuration: Duration) : DeviceEvent()
}

/**
 * Exposes raw screen-state signals as a Flow. A widget that wants to react
 * to "user woke up after a long sleep" subscribes to events in onAttached().
 *
 * The flow registers its BroadcastReceiver when collected and unregisters
 * when the collector is done — battery use is proportional to the number of
 * active collectors, not always-on.
 *
 * Screen broadcasts cannot be declared in the manifest on modern Android;
 * they must be registered at runtime, which this implementation does via
 * callbackFlow.
 */
interface DeviceActivitySignals {
    val events: Flow<DeviceEvent>
}

class RealDeviceActivitySignals(private val context: Context) : DeviceActivitySignals {

    private val inactivityThreshold = Duration.ofHours(2)

    override val events: Flow<DeviceEvent> = callbackFlow {
        var lastScreenOffElapsed = SystemClock.elapsedRealtime()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        lastScreenOffElapsed = SystemClock.elapsedRealtime()
                        trySend(DeviceEvent.ScreenOff)
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        trySend(DeviceEvent.ScreenOn)
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        val elapsed = Duration.ofMillis(
                            SystemClock.elapsedRealtime() - lastScreenOffElapsed
                        )
                        if (elapsed >= inactivityThreshold) {
                            trySend(DeviceEvent.WokeAfterInactivity(elapsed))
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(receiver, filter)

        awaitClose { context.unregisterReceiver(receiver) }
    }
}
