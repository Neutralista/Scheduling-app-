package com.waypoint.app.script

import androidx.compose.runtime.Composable
import com.waypoint.app.planner.EventPlannerRegistry
import com.waypoint.app.planner.SleepScheduleStore
import com.waypoint.app.planner.TaskConditionSpec
import com.waypoint.app.planner.TaskRequest
import com.waypoint.app.signal.WorkScheduleSignals
import com.waypoint.app.widget.WorkScheduleCard
import kotlinx.coroutines.flow.MutableStateFlow

class WorkScheduleScript(
    private val ws: WorkScheduleSignals,
    private val sleepStore: SleepScheduleStore,
    private val eventPlanner: EventPlannerRegistry,
    private val sleepRefresh: MutableStateFlow<Int>,
    private val taskManager: TaskManagerScript? = null
) : AppScript {

    override val id = "built_in.work_schedule"
    override val displayName = "Work Schedule"
    override val hasWidget = true
    override val canResetToDefaults = true

    override fun onAttached(env: ScriptEnvironment) {
        submitWorkTasks()
    }

    override suspend fun resetToDefaults() {
        ws.resetToDefaults()
        taskManager?.retractBySource(id)
    }

    private fun submitWorkTasks() {
        val tm = taskManager ?: return
        tm.retractBySource(id)

        val schedule   = ws.getTodaySchedule()
        val shiftStart = schedule.shiftStart ?: return

        // Prep: 30-min slot in the 60-min window before shift start
        val startTotalMin  = shiftStart.hour * 60 + shiftStart.minute
        val prepStartMin   = (startTotalMin - 60).coerceAtLeast(0)
        tm.submitTask(
            TaskRequest(
                id = "work_shift_prep",
                title = "Shift prep",
                durationMinutes = 30,
                priority = 8,
                sourceScriptId = id,
                conditions = listOf(
                    TaskConditionSpec("workDayOnly"),
                    TaskConditionSpec(
                        type  = "timeWindow",
                        start = "%02d:%02d".format(prepStartMin / 60, prepStartMin % 60),
                        end   = shiftStart.displayString
                    )
                )
            )
        )

        // Wind-down: 30-min slot placed directly after shift end via AfterShift pass.
        // Using afterShift (not a timeWindow) so night shifts ending at/past midnight
        // are handled correctly — timeWindow resolves against the current day's clock,
        // which is before the post-midnight shift end.
        val shiftEnd = schedule.shiftEnd ?: return
        tm.submitTask(
            TaskRequest(
                id = "work_wind_down",
                title = "Wind down",
                durationMinutes = 30,
                priority = 7,
                sourceScriptId = id,
                conditions = listOf(
                    TaskConditionSpec("workDayOnly"),
                    TaskConditionSpec("afterShift")
                )
            )
        )
    }

    @Composable
    override fun SettingsContent() {
        WorkScheduleCard(
            ws = ws,
            onShiftEnd = {
                sleepStore.syncToRegistry(eventPlanner, ws)
                sleepRefresh.value++
                submitWorkTasks()
            }
        )
    }

    @Composable
    override fun WidgetContent(state: ScriptState?, onStateChange: (ScriptState) -> Unit) {
        WorkScheduleCard(
            ws = ws,
            onShiftEnd = {
                sleepStore.syncToRegistry(eventPlanner, ws)
                sleepRefresh.value++
                submitWorkTasks()
            }
        )
    }
}
