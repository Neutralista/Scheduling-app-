package com.waypoint.app

import android.app.Application
import com.waypoint.app.background.ScriptTickWorker
import com.waypoint.app.notification.NotificationHelper
import com.waypoint.app.notification.ReminderScheduler
import com.waypoint.app.persistence.ScriptStateStore
import com.waypoint.app.script.ScriptRegistry
import com.waypoint.app.script.ScriptStore
import com.waypoint.app.script.SleepScheduleScript
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

        AppLogger.init(filesDir)

        scriptStateStore = ScriptStateStore(applicationContext)
        scriptStore = ScriptStore(applicationContext)
        env = RealScriptEnvironment(applicationContext, scriptStateStore, appScope)

        // Shared refresh signal so WorkScheduleScript can trigger SleepScheduleScript recompose
        val sleepRefresh = MutableStateFlow(0)

        // Register built-in scripts
        ScriptRegistry.register(
            WorkScheduleScript(env.workSchedule, env.sleepStore, env.eventPlanner, sleepRefresh),
            env
        )
        ScriptRegistry.register(
            SleepScheduleScript(env.sleepStore, env.eventPlanner, env.workSchedule, sleepRefresh),
            env
        )

        // Sync sleep schedule into the event planner on launch
        env.sleepStore.syncToRegistry(env.eventPlanner, env.workSchedule)

        // Re-register any user scripts saved in a previous session
        scriptStore.loadAll().forEach { module ->
            ScriptRegistry.register(module, env)
        }

        NotificationHelper.createChannel(this)
        NotificationHelper.createShiftChannel(this)
        NotificationHelper.createScriptsChannel(this)
        ReminderScheduler.schedule(this, hourOfDay = 9)
        ScriptTickWorker.schedule(this)
    }
}
