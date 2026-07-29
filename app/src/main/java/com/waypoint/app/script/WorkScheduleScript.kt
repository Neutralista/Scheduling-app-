package com.waypoint.app.script

import androidx.compose.runtime.Composable
import com.waypoint.app.planner.EventPlannerRegistry
import com.waypoint.app.planner.SleepScheduleStore
import com.waypoint.app.signal.WorkScheduleSignals
import com.waypoint.app.widget.WorkScheduleCard
import kotlinx.coroutines.flow.MutableStateFlow

class WorkScheduleScript(
    private val ws: WorkScheduleSignals,
    private val sleepStore: SleepScheduleStore,
    private val eventPlanner: EventPlannerRegistry,
    private val sleepRefresh: MutableStateFlow<Int>
) : AppScript {

    override val id = "built_in.work_schedule"
    override val displayName = "Work Schedule"
    override val hasWidget = true
    override val canResetToDefaults = true

    override suspend fun resetToDefaults() = ws.resetToDefaults()

    @Composable
    override fun SettingsContent() {
        WorkScheduleCard(
            ws = ws,
            onShiftEnd = {
                sleepStore.syncToRegistry(eventPlanner, ws)
                sleepRefresh.value++
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
            }
        )
    }
}
