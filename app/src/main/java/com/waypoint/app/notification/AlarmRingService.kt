package com.waypoint.app.notification

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.waypoint.app.AppLogger
import com.waypoint.app.R
import com.waypoint.app.WaypointApplication

class AlarmRingService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> {
                val volume     = intent.getFloatExtra(EXTRA_VOLUME, 1.0f)
                val channel    = intent.getStringExtra(EXTRA_CHANNEL) ?: SleepNotificationHelper.CH_WAKE_FULL
                val title      = intent.getStringExtra(EXTRA_TITLE) ?: "Wake up!"
                val fullScreen = intent.getBooleanExtra(EXTRA_FULL_SCREEN, true)
                startRinging(volume, channel, title, fullScreen)
            }
            ACTION_DISMISS -> dismiss()
            ACTION_SNOOZE  -> snooze()
            else           -> { startForegroundPlaceholder(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun startRinging(volume: Float, channel: String, title: String, fullScreen: Boolean) {
        AppLogger.i(TAG, "startRinging: volume=$volume channel=$channel fullScreen=$fullScreen")
        stopSound()
        stopVibration()
        showNotification(channel, title, fullScreen)
        startSound(volume)
        startVibration(volume)
    }

    private fun dismiss() {
        AppLogger.i(TAG, "dismiss")
        stopSoundAndVibration()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // User explicitly dismissed the wake alarm — most authoritative wake signal available.
        // recordActive() will close the sleeping cycle and open a new one.
        (applicationContext as? WaypointApplication)?.cycleTracker?.recordActive()
    }

    private fun snooze() {
        val snoozeMs = System.currentTimeMillis() + SNOOZE_MS
        AppLogger.i(TAG, "snooze: rescheduling to $snoozeMs")
        stopSoundAndVibration()
        WakeAlarmScheduler.scheduleRingAlarm(this, snoozeMs)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showNotification(channel: String, title: String, fullScreen: Boolean) {
        val dismissPi = pendingServiceIntent(0, ACTION_DISMISS)
        val snoozePi  = pendingServiceIntent(1, ACTION_SNOOZE)
        val ringActivityPi = PendingIntent.getActivity(
            this, 2,
            Intent(this, AlarmRingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("Waypoint alarm")
            .setContentIntent(ringActivityPi)
            .addAction(0, "Dismiss", dismissPi)
            .addAction(0, "Snooze ${SNOOZE_MS / 60_000} min", snoozePi)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSound(null)
        if (fullScreen) builder.setFullScreenIntent(ringActivityPi, true)
        startForeground(NOTIF_ID, builder.build())
    }

    private fun startForegroundPlaceholder() {
        val notif = NotificationCompat.Builder(this, SleepNotificationHelper.CH_WAKE_FULL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Alarm")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun startSound(volume: Float) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmRingService, uri)
                isLooping = true
                setVolume(volume, volume)
                prepare()
                start()
            }
            AppLogger.i(TAG, "startSound: playing $uri volume=$volume")
        } catch (e: Exception) {
            AppLogger.e(TAG, "startSound threw", e)
        }
    }

    private fun stopSound() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun startVibration(volume: Float) {
        val pattern = when {
            volume <= 0.4f -> longArrayOf(0, 200, 600)                        // gentle: short single pulse
            volume <= 0.75f -> longArrayOf(0, 400, 400, 400, 800)             // medium: two pulses
            else           -> longArrayOf(0, 600, 400, 600, 400, 1000, 800)   // full: escalating
        }
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "startVibration threw", e)
        }
    }

    private fun stopVibration() {
        vibrator?.cancel()
        vibrator = null
    }

    private fun stopSoundAndVibration() {
        stopSound()
        stopVibration()
    }

    private fun pendingServiceIntent(reqCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this, reqCode,
            Intent(this, AlarmRingService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    override fun onDestroy() {
        super.onDestroy()
        stopSoundAndVibration()
    }

    companion object {
        const val ACTION_RING    = "com.waypoint.app.ALARM_RING"
        const val ACTION_DISMISS = "com.waypoint.app.ALARM_DISMISS"
        const val ACTION_SNOOZE  = "com.waypoint.app.ALARM_SNOOZE"
        const val EXTRA_VOLUME      = "volume"
        const val EXTRA_CHANNEL     = "channel"
        const val EXTRA_TITLE       = "title"
        const val EXTRA_FULL_SCREEN = "full_screen"
        private const val NOTIF_ID  = 112
        private const val SNOOZE_MS = 10 * 60_000L
        private const val TAG       = "AlarmRingService"
    }
}
