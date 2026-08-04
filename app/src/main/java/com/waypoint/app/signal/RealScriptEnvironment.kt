package com.waypoint.app.signal

import android.content.Context
import com.waypoint.app.alarm.AlarmSignals
import com.waypoint.app.alarm.AlarmStore
import com.waypoint.app.alarm.RealAlarmSignals
import com.waypoint.app.persistence.CountdownStore
import com.waypoint.app.persistence.ScriptStateStore
import com.waypoint.app.persistence.SharedMemoryStore
import com.waypoint.app.persistence.StreakStore
import com.waypoint.app.persistence.TaskCompletionStore
import com.waypoint.app.planner.BufferRulesStore
import com.waypoint.app.planner.EventPlannerRegistry
import com.waypoint.app.planner.SleepScheduleStore
import com.waypoint.app.planner.TaskExecutionStore
import com.waypoint.app.planner.TaskQueueStore
import com.waypoint.app.script.ScriptEnvironment
import com.waypoint.app.script.ScriptState
import com.waypoint.app.script.TaskManagerScript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Single shared ScriptEnvironment for the app.
 * The in-memory [stateCache] is kept in sync by HomeViewModel collecting
 * ScriptStateStore.allStates() and calling [updateCache] on each emission.
 */
class RealScriptEnvironment(
    override val context: Context,
    private val stateStore: ScriptStateStore,
    private val scope: CoroutineScope
) : ScriptEnvironment {

    override val deviceActivity: DeviceActivitySignals = RealDeviceActivitySignals(context)
    override val appUsage: AppUsageSignals = RealAppUsageSignals(context)
    override val healthConnect: HealthConnectSignals = RealHealthConnectSignals(context)
    override val calendar: CalendarSignals = RealCalendarSignals(context)
    override val alarms: AlarmSignals = RealAlarmSignals(context, AlarmStore(context))
    override val eventPlanner: EventPlannerRegistry = EventPlannerRegistry()
    val taskExecutionStore: TaskExecutionStore = TaskExecutionStore(context)
    val bufferRulesStore: BufferRulesStore = BufferRulesStore(context)
    override val taskManager: TaskManagerScript = TaskManagerScript(
        store = TaskQueueStore(context),
        registry = eventPlanner,
        completions = TaskCompletionStore(context),
        executions = taskExecutionStore
    )
    override val sleepStore: SleepScheduleStore = SleepScheduleStore(context)
    override val memory: SharedMemoryStore = SharedMemoryStore(context)
    override val streak: StreakStore = StreakStore(context)
    override val countdown: CountdownStore = CountdownStore(context)
    override val location: LocationSignals = RealLocationSignals(context)

    // Synchronous cache for JS inter-script reads
    private val stateCache = mutableMapOf<String, ScriptState>()

    fun updateCache(states: Map<String, ScriptState>) {
        stateCache.clear()
        stateCache.putAll(states)
    }

    override fun getScriptState(scriptId: String): ScriptState? = stateCache[scriptId]

    override fun setScriptState(scriptId: String, state: ScriptState) {
        stateCache[scriptId] = state
        scope.launch(Dispatchers.IO) { stateStore.save(scriptId, state) }
    }
}
