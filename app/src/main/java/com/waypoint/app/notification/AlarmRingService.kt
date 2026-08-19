package com.waypoint.app.notification

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.waypoint.app.AppLogger
import com.waypoint.app.R
import com.waypoint.app.WaypointApplication
import com.waypoint.app.alarm.UserAlarmScheduler

class AlarmRingService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING    -> startRinging(intent)
            ACTION_DISMISS -> dismiss()
            ACTION_SNOOZE  -> snooze(intent)
            else           -> { startForegroundPlaceholder(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun startRinging(intent: Intent) {
        val volume     = intent.getFloatExtra(EXTRA_VOLUME, 1.0f)
        val channel    = intent.getStringExtra(EXTRA_CHANNEL) ?: SleepNotificationHelper.CH_WAKE_FULL
        val title      = intent.getStringExtra(EXTRA_TITLE) ?: "Wake up!"
        val fullScreen = intent.getBooleanExtra(EXTRA_FULL_SCREEN, true)
        val soundUri   = intent.getStringExtra(EXTRA_SOUND_URI)
        val vibrate    = intent.getBooleanExtra(EXTRA_VIBRATE, true)
        AppLogger.i(TAG, "startRinging: volume=$volume channel=$channel fullScreen=$fullScreen vibrate=$vibrate")
        if (intent.getBooleanExtra(EXTRA_MAX_VOLUME, false)) applyMaxVolume()
        stopSound()
        stopVibration()
        showNotification(channel, title, fullScreen, intent)
        startSound(volume, soundUri)
        if (vibrate) startVibration(volume)
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

    private fun snooze(intent: Intent?) {
        val source        = intent?.getStringExtra(EXTRA_SOURCE) ?: SOURCE_SLEEP
        val alarmId       = intent?.getStringExtra(EXTRA_ALARM_ID)
        val label         = intent?.getStringExtra(EXTRA_TITLE) ?: "Alarm"
        val snoozeMinutes = intent?.getIntExtra(EXTRA_SNOOZE_MINUTES, DEFAULT_SNOOZE_MINUTES) ?: DEFAULT_SNOOZE_MINUTES
        val snoozeMs = System.currentTimeMillis() + snoozeMinutes * 60_000L
        AppLogger.i(TAG, "snooze: source=$source alarmId=$alarmId minutes=$snoozeMinutes rescheduling to $snoozeMs")
        stopSoundAndVibration()
        if (source == SOURCE_USER && alarmId != null) {
            UserAlarmScheduler.scheduleSnooze(this, alarmId, label, snoozeMs)
        } else {
            WakeAlarmScheduler.scheduleRingAlarm(this, snoozeMs)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showNotification(channel: String, title: String, fullScreen: Boolean, sourceIntent: Intent) {
        val extras = sourceIntent.extras ?: Bundle()
        val dismissPi = pendingServiceIntent(0, ACTION_DISMISS, extras)
        val snoozePi  = pendingServiceIntent(1, ACTION_SNOOZE, extras)
        val ringActivityPi = PendingIntent.getActivity(
            this, 2,
            Intent(this, AlarmRingActivity::class.java)
                .putExtras(extras)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozeMinutes = sourceIntent.getIntExtra(EXTRA_SNOOZE_MINUTES, DEFAULT_SNOOZE_MINUTES)
        val builder = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("Waypoint alarm")
            .setContentIntent(ringActivityPi)
            .addAction(0, "Dismiss", dismissPi)
            .addAction(0, "Snooze $snoozeMinutes min", snoozePi)
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

    private fun applyMaxVolume() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        } catch (e: Exception) {
            AppLogger.e(TAG, "applyMaxVolume threw", e)
        }
    }

    private fun startSound(volume: Float, soundUri: String?) {
        try {
            val uri = soundUri?.let { Uri.parse(it) }
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
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

    private fun pendingServiceIntent(reqCode: Int, action: String, extras: Bundle): PendingIntent =
        PendingIntent.getService(
            this, reqCode,
            Intent(this, AlarmRingService::class.java).apply {
                this.action = action
                putExtras(extras)
            },
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
        const val EXTRA_VOLUME         = "volume"
        const val EXTRA_CHANNEL        = "channel"
        const val EXTRA_TITLE          = "title"
        const val EXTRA_FULL_SCREEN    = "full_screen"
        /** Which subsystem started this ring — determines what a Snooze tap reschedules. */
        const val EXTRA_SOURCE         = "source"
        const val EXTRA_ALARM_ID       = "alarm_id"
        const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
        const val EXTRA_SOUND_URI      = "sound_uri"
        const val EXTRA_MAX_VOLUME     = "max_volume_override"
        const val EXTRA_VIBRATE        = "vibrate"
        const val SOURCE_USER  = "user"
        const val SOURCE_SLEEP = "sleep"
        private const val NOTIF_ID  = 112
        private const val DEFAULT_SNOOZE_MINUTES = 10
        private const val TAG       = "AlarmRingService"
    }
}
