package com.waypoint.app

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import com.waypoint.app.background.CalendarSyncWorker
import com.waypoint.app.background.ScriptTickWorker
import com.waypoint.app.cycle.CycleTracker
import com.waypoint.app.notification.BlockNotificationHelper
import com.waypoint.app.notification.NotificationHelper
import com.waypoint.app.notification.ReminderScheduler
import com.waypoint.app.notification.SleepNotificationHelper
import com.waypoint.app.planner.BlockAlarmScheduler
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

data class InitStep(val name: String, val ok: Boolean, val error: Throwable? = null)

class WaypointApplication : Application() {

    /** Non-null if WaypointApplication.onCreate() threw before completing. */
    var startupCrash: Throwable? = null
        private set

    /** Ordered record of each init step — populated synchronously in onCreate(). */
    val initSteps: MutableList<InitStep> = mutableListOf()

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
            initSteps += InitStep("Logger", true)
        } catch (e: Throwable) {
            android.util.Log.e("WaypointApp", "AppLogger.init failed", e)
            initSteps += InitStep("Logger", false, e)
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

        var currentStep = "init"
        try {
            currentStep = "Script state store"
            scriptStateStore = ScriptStateStore(applicationContext)
            initSteps += InitStep(currentStep, true)

            currentStep = "Script store"
            scriptStore = ScriptStore(applicationContext)
            initSteps += InitStep(currentStep, true)

            currentStep = "Script environment"
            env = RealScriptEnvironment(applicationContext, scriptStateStore, appScope)
            initSteps += InitStep(currentStep, true)

            currentStep = "Cycle tracker"
            cycleTracker = CycleTracker(applicationContext)
            env.taskManager.completions.getCycleId = { cycleTracker.store.loadCurrent()?.id ?: "" }
            cycleTracker.onNewCycle = { env.taskManager.completions.clearAll() }
            initSteps += InitStep(currentStep, true)

            currentStep = "Built-in scripts"
            val sleepRefresh = MutableStateFlow(0)
            val sleepLogStore = SleepLogStore(applicationContext)
            ScriptRegistry.register(env.taskManager, env)
            ScriptRegistry.register(
                SleepScheduleScript(env.sleepStore, env.eventPlanner, sleepRefresh, sleepLogStore),
                env
            )
            initSteps += InitStep(currentStep, true)

            currentStep = "Bundled scripts"
            scriptStore.seedBundled()
            scriptStore.delete("user.week_planner")
            initSteps += InitStep(currentStep, true)

            currentStep = "User scripts"
            scriptStore.loadAll().forEach { module -> ScriptRegistry.register(module, env) }
            initSteps += InitStep(currentStep, true)

            currentStep = "Notifications & workers"
            NotificationHelper.createScriptsChannel(this)
            SleepNotificationHelper.createChannels(this)
            BlockNotificationHelper.createChannel(this)
            ReminderScheduler.cancel(this)
            ScriptTickWorker.schedule(this)
            CalendarSyncWorker.schedule(this)
            scheduleBlockAlarms(this)
            initSteps += InitStep(currentStep, true)

        } catch (e: Throwable) {
            android.util.Log.e("WaypointApp", "onCreate crashed at '$currentStep': ${e.javaClass.name}: ${e.message}", e)
            try { AppLogger.e("App", "onCreate crashed at '$currentStep'", e) } catch (_: Throwable) {}
            initSteps += InitStep(currentStep, false, e)
            startupCrash = e
            // Do NOT rethrow — let MainActivity show a crash recovery UI instead.
        }
    }

    fun scheduleBlockAlarms(context: android.content.Context = applicationContext) {
        try {
            val store = com.waypoint.app.planner.NamedBlockStore(context)
            val today = LocalDate.now()
            val now = System.currentTimeMillis()
            store.resolveForDate(today).forEach { (block, sched) ->
                val startMs = today.atTime(sched.startHour, sched.startMinute)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val endMs = if (sched.endHour >= 0) {
                    val e = today.atTime(sched.endHour, sched.endMinute)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    if (e > startMs) e else e + 24 * 3600_000L
                } else startMs + block.estimatedMinutes * 60_000L
                if (startMs > now) {
                    BlockAlarmScheduler.schedule(context, block.id, block.name, block.colorArgb, startMs, endMs)
                }
            }
        } catch (e: Throwable) {
            AppLogger.e("WaypointApp", "scheduleBlockAlarms failed", e)
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
