package com.waypoint.app

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import com.waypoint.app.background.CalendarSyncWorker
import com.waypoint.app.background.ScriptTickWorker
import com.waypoint.app.cycle.CycleTracker
import com.waypoint.app.notification.NotificationHelper
import com.waypoint.app.notification.ReminderScheduler
import com.waypoint.app.notification.SleepNotificationHelper
import com.waypoint.app.planner.SleepLogStore
import com.waypoint.app.persistence.ScriptStateStore
import com.waypoint.app.script.ScriptRegistry
import com.waypoint.app.script.ScriptStore
import com.waypoint.app.script.SleepScheduleScript
import com.waypoint.app.script.TaskManagerScript
import com.waypoint.app.signal.RealScriptEnvironment
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WaypointApplication : Application() {

    /** Non-null if WaypointApplication.onCreate() threw before completing. */
    var startupCrash: Throwable? = null
        private set

    lateinit var env: RealScriptEnvironment
        private set
    lateinit var scriptStateStore: ScriptStateStore
        private set
    lateinit var scriptStore: ScriptStore
        private set
    lateinit var cycleTracker: CycleTracker
        private set

    private val appScope = MainScope()

    override fun onCreate() {
        super.onCreate()

        try {
            AppLogger.init(filesDir)
        } catch (e: Throwable) {
            android.util.Log.e("WaypointApp", "AppLogger.init failed", e)
        }

        val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                AppLogger.e("CRASH", "Uncaught exception on thread '${thread.name}'", throwable)
            } catch (_: Throwable) {}
            try {
                writeCrashToDownloads(throwable)
            } catch (_: Throwable) {}
            defaultExceptionHandler?.uncaughtException(thread, throwable)
        }

        try {
            scriptStateStore = ScriptStateStore(applicationContext)
            scriptStore = ScriptStore(applicationContext)
            env = RealScriptEnvironment(applicationContext, scriptStateStore, appScope)
            cycleTracker = CycleTracker(applicationContext)
            env.taskManager.completions.getCycleId = { cycleTracker.store.loadCurrent()?.id ?: "" }
            cycleTracker.onNewCycle = {
                // Reset per-cycle state so the new wake period starts clean.
                env.taskManager.completions.clearAll()
            }

            val sleepRefresh = MutableStateFlow(0)
            val sleepLogStore = SleepLogStore(applicationContext)

            // Register built-in scripts — task manager first so it's ready for others
            ScriptRegistry.register(env.taskManager, env)
            ScriptRegistry.register(
                SleepScheduleScript(env.sleepStore, env.eventPlanner, sleepRefresh, sleepLogStore),
                env
            )

            // Seed bundled scripts (assets/scripts/*.js) on first install
            scriptStore.seedBundled()
            // Remove deprecated bundled scripts
            scriptStore.delete("user.week_planner")

            // Re-register any user scripts saved in a previous session
            scriptStore.loadAll().forEach { module ->
                ScriptRegistry.register(module, env)
            }

            NotificationHelper.createScriptsChannel(this)
            SleepNotificationHelper.createChannels(this)
            ReminderScheduler.cancel(this) // daily habit reminder disabled until habits feature is built
            ScriptTickWorker.schedule(this)
            CalendarSyncWorker.schedule(this)
        } catch (e: Throwable) {
            android.util.Log.e("WaypointApp", "onCreate crashed: ${e.javaClass.name}: ${e.message}", e)
            try { AppLogger.e("App", "onCreate crashed", e) } catch (_: Throwable) {}
            startupCrash = e
            // Do NOT rethrow — let MainActivity show a crash recovery UI instead.
        }
    }

    private fun writeCrashToDownloads(throwable: Throwable) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val report = buildString {
            appendLine("Waypoint crash — $ts")
            appendLine("Thread: ${Thread.currentThread().name}")
            appendLine()
            appendLine("=== Exception ===")
            appendLine(throwable.stackTraceToString())
            appendLine()
            appendLine("=== App Log ===")
            try {
                appendLine(File(filesDir, "waypoint_current.log").readText())
            } catch (e: Throwable) {
                appendLine("(could not read log: ${e.message})")
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "waypoint_crash_$ts.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) }
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        contentResolver.update(uri, done, null, null)
    }
}
