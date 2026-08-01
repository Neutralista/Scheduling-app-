package com.waypoint.app

import android.app.Application
import com.waypoint.app.background.CalendarSyncWorker
import com.waypoint.app.background.ScriptTickWorker
import com.waypoint.app.notification.NotificationHelper
import com.waypoint.app.notification.ReminderScheduler
import com.waypoint.app.notification.SleepNotificationHelper
import com.waypoint.app.planner.SleepLogStore
import com.waypoint.app.persistence.ScriptStateStore
import com.waypoint.app.script.ScriptRegistry
import com.waypoint.app.script.ScriptStore
import com.waypoint.app.script.SleepScheduleScript
import com.waypoint.app.script.TaskManagerScript
import com.waypoint.app.script.WorkScheduleScript
import com.waypoint.app.signal.RealScriptEnvironment
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow

class WaypointApplication : Application() {

    lateinit var env: RealScriptEnvironment
        private set
    lateinit var scriptStateStore: ScriptStateStore
        private set
    lateinit var scriptStore: ScriptStore
        private set

    private val appScope = MainScope()

    override fun onCreate() {
        super.onCreate()

        try {
            AppLogger.init(filesDir)
        } catch (e: Throwable) {
            android.util.Log.e("WaypointApp", "AppLogger.init failed", e)
        }

        try {
            scriptStateStore = ScriptStateStore(applicationContext)
            scriptStore = ScriptStore(applicationContext)
            env = RealScriptEnvironment(applicationContext, scriptStateStore, appScope)

            // Shared refresh signal so WorkScheduleScript can trigger SleepScheduleScript recompose
            val sleepRefresh = MutableStateFlow(0)
            val sleepLogStore = SleepLogStore(applicationContext)

            // Register built-in scripts — task manager first so it's ready for others
            ScriptRegistry.register(env.taskManager, env)
            ScriptRegistry.register(
                WorkScheduleScript(env.workSchedule, env.sleepStore, env.eventPlanner, sleepRefresh, env.taskManager),
                env
            )
            ScriptRegistry.register(
                SleepScheduleScript(env.sleepStore, env.eventPlanner, env.workSchedule, sleepRefresh, sleepLogStore),
                env
            )

            // Seed bundled scripts (assets/scripts/*.js) on first install
            scriptStore.seedBundled()

            // Re-register any user scripts saved in a previous session
            scriptStore.loadAll().forEach { module ->
                ScriptRegistry.register(module, env)
            }

            NotificationHelper.createShiftChannel(this)
            NotificationHelper.createScriptsChannel(this)
            SleepNotificationHelper.createChannels(this)
            ReminderScheduler.cancel(this) // daily habit reminder disabled until habits feature is built
            ScriptTickWorker.schedule(this)
            CalendarSyncWorker.schedule(this)
        } catch (e: Throwable) {
            android.util.Log.e("WaypointApp", "onCreate crashed at: ${e.javaClass.name}: ${e.message}", e)
            try { AppLogger.e("App", "onCreate crashed: ${e.javaClass.name}: ${e.message}", e) } catch (_: Throwable) {}
            throw e
        }
    }
}
