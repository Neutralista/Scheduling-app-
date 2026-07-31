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

class AlarmRingService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING    -> startRinging()
            ACTION_DISMISS -> dismiss()
            ACTION_SNOOZE  -> snooze()
            else           -> { startForegroundPlaceholder(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun startRinging() {
        AppLogger.i(TAG, "startRinging")
        stopSound()
        stopVibration()
        showNotification()
        startSound()
        startVibration()
    }

    private fun dismiss() {
        AppLogger.i(TAG, "dismiss")
        stopSoundAndVibration()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun snooze() {
        val snoozeMs = System.currentTimeMillis() + SNOOZE_MS
        AppLogger.i(TAG, "snooze: rescheduling to $snoozeMs")
        stopSoundAndVibration()
        WakeAlarmScheduler.scheduleRingAlarm(this, snoozeMs)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showNotification() {
        val dismissPi = pendingServiceIntent(0, ACTION_DISMISS)
        val snoozePi  = pendingServiceIntent(1, ACTION_SNOOZE)
        val fullScreenPi = PendingIntent.getActivity(
            this, 2,
            Intent(this, AlarmRingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, SleepNotificationHelper.CH_WAKE_FULL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Wake up!")
            .setContentText("Waypoint alarm")
            .setContentIntent(fullScreenPi)
            .setFullScreenIntent(fullScreenPi, true)
            .addAction(0, "Dismiss", dismissPi)
            .addAction(0, "Snooze ${SNOOZE_MS / 60_000} min", snoozePi)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSound(null)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun startForegroundPlaceholder() {
        val notif = NotificationCompat.Builder(this, SleepNotificationHelper.CH_WAKE_FULL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Alarm")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun startSound() {
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
                prepare()
                start()
            }
            AppLogger.i(TAG, "startSound: playing $uri")
        } catch (e: Exception) {
            AppLogger.e(TAG, "startSound threw", e)
        }
    }

    private fun stopSound() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun startVibration() {
        val pattern = longArrayOf(0, 600, 400, 600, 400, 1000, 800)
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
        private const val NOTIF_ID   = 112
        private const val SNOOZE_MS  = 10 * 60_000L
        private const val TAG        = "AlarmRingService"
    }
}
