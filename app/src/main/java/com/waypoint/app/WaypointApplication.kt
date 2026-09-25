package com.waypoint.app

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import com.waypoint.app.alarm.BootAlarmMirror
import com.waypoint.app.background.CalendarSyncWorker
import com.waypoint.app.background.ScriptTickWorker
import com.waypoint.app.cycle.CycleTracker
import com.waypoint.app.notification.BlockNotificationHelper
import com.waypoint.app.notification.NotificationHelper
import com.waypoint.app.notification.ReminderScheduler
import com.waypoint.app.notification.SleepNotificationHelper
import com.waypoint.app.planner.BlockAlarmScheduler
import com.waypoint.app.planner.BlockSessionStore
import com.waypoint.app.planner.NamedBlockStore
import com.waypoint.app.planner.SleepLogStore
import com.waypoint.app.persistence.ScriptStateStore
import com.waypoint.app.script.ScriptRegistry
import com.waypoint.app.script.ScriptStore
import com.waypoint.app.script.SleepScheduleScript
import com.waypoint.app.script.TaskManagerScript
import com.waypoint.app.signal.RealScriptEnvironment
import com.waypoint.app.ui.theme.ThemeStore
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

    // Set up in initialize(). Each getter finishes setup first if this process started before the
    // phone was unlocked (see onCreate) and has been unlocked since.
    private lateinit var _env: RealScriptEnvironment
    private lateinit var _scriptStateStore: ScriptStateStore
    private lateinit var _scriptStore: ScriptStore
    private lateinit var _cycleTracker: CycleTracker
    private lateinit var _themeStore: ThemeStore
    val env: RealScriptEnvironment get() { ensureInitialized(); return _env }
    val scriptStateStore: ScriptStateStore get() { ensureInitialized(); return _scriptStateStore }
    val scriptStore: ScriptStore get() { ensureInitialized(); return _scriptStore }
    val cycleTracker: CycleTracker get() { ensureInitialized(); return _cycleTracker }
    val themeStore: ThemeStore get() { ensureInitialized(); return _themeStore }

    /** True once initialize() has run. False while the phone hasn't been unlocked since a restart. */
    @Volatile
    var isInitialized = false
        private set
    private var initStarted = false

    private val appScope = MainScope()

    override fun onCreate() {
        super.onCreate()

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

        // Started before the first unlock after a restart, for the alarm parts that run then
        // (LockedBootReceiver and the ring path). Normal storage can't be read until unlock, and
        // every step below reads it — reading it now would throw. Setup waits for the first use
        // after unlock (ensureInitialized, via the getters above).
        if (!BootAlarmMirror.isUserUnlocked(this)) return
        ensureInitialized()
    }

    /** Runs the full setup once, as soon as the phone has been unlocked. */
    fun ensureInitialized() {
        if (isInitialized || !BootAlarmMirror.isUserUnlocked(this)) return
        synchronized(this) {
            if (initStarted) return
            initStarted = true
            initialize()
            isInitialized = true
        }
    }

    private fun initialize() {
        try {
            AppLogger.init(filesDir)
            initSteps += InitStep("Logger", true)
        } catch (e: Throwable) {
            android.util.Log.e("WaypointApp", "AppLogger.init failed", e)
            initSteps += InitStep("Logger", false, e)
        }

        _themeStore = ThemeStore(applicationContext)
        // Keeps Might's look in step even if it was installed or reset since the last change.
        com.waypoint.app.integration.MightThemeSync.send(this, _themeStore)

        var currentStep = "init"
        try {
            currentStep = "Script state store"
            _scriptStateStore = ScriptStateStore(applicationContext)
            initSteps += InitStep(currentStep, true)

            currentStep = "Script store"
            _scriptStore = ScriptStore(applicationContext)
            initSteps += InitStep(currentStep, true)

            currentStep = "Script environment"
            _env = RealScriptEnvironment(applicationContext, _scriptStateStore, appScope)
            initSteps += InitStep(currentStep, true)

            currentStep = "Cycle tracker"
            _cycleTracker = CycleTracker(applicationContext)
            _env.taskManager.completions.getCycleId = { _cycleTracker.store.loadCurrent()?.id ?: "" }
            _cycleTracker.onNewCycle = { wakeMillis -> _env.taskManager.completions.retainDoneSince(wakeMillis) }
            BlockSessionStore.taskCounter = { session ->
                val tasks = NamedBlockStore(applicationContext)
                    .resolveActiveTasks(session.blockId, LocalDate.parse(session.date))
                tasks.count { _env.taskManager.isDone(it.id) } to tasks.size
            }
            // Now that it can count tasks: log a session that timed out while the app was closed.
            _env.blockSessionStore.loadCurrent()
            // Routines are auto-placed blocks now; converts any left from before (once).
            runCatching { com.waypoint.app.planner.RoutineMigration.run(_env.taskManager, NamedBlockStore(applicationContext)) }
                .onFailure { AppLogger.e("WaypointApplication", "routine migration failed", it) }
            // Housekeeping: both kept every day's entries forever.
            runCatching {
                NamedBlockStore(applicationContext).pruneOldDates()
                _env.taskManager.executions.pruneOld()
            }
            initSteps += InitStep(currentStep, true)

            currentStep = "Built-in scripts"
            val sleepRefresh = MutableStateFlow(0)
            val sleepLogStore = SleepLogStore(applicationContext)
            ScriptRegistry.register(_env.taskManager, _env)
            ScriptRegistry.register(
                SleepScheduleScript(_env.sleepStore, _env.eventPlanner, sleepRefresh, sleepLogStore),
                _env
            )
            initSteps += InitStep(currentStep, true)

            currentStep = "Bundled scripts"
            _scriptStore.seedBundled()
            _scriptStore.delete("user.week_planner")
            initSteps += InitStep(currentStep, true)

            currentStep = "User scripts"
            _scriptStore.loadAll().forEach { module -> ScriptRegistry.register(module, _env) }
            initSteps += InitStep(currentStep, true)

            currentStep = "Notifications & workers"
            NotificationHelper.createScriptsChannel(this)
            SleepNotificationHelper.createChannels(this)
            BlockNotificationHelper.createChannel(this)
            com.waypoint.app.notification.TaskReminderScheduler.createChannel(this)
            ReminderScheduler.cancel(this)
            ScriptTickWorker.schedule(this)
            CalendarSyncWorker.schedule(this)
            scheduleBlockAlarms(this)
            com.waypoint.app.alarm.AlarmBlockSync.sync(this)
            // Seeds the before-unlock copy for installs that predate it; kept current after this.
            runCatching { BootAlarmMirror.saveUserAlarms(this, _env.alarms.getAll()) }
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
                } else startMs + store.effectiveDurationMinutes(block, today) * 60_000L
                if (startMs > now && block.notificationsEnabled) {
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
