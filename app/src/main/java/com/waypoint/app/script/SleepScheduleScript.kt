package com.waypoint.app.script

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.waypoint.app.planner.EventPlannerRegistry
import com.waypoint.app.planner.SleepScheduleCard
import com.waypoint.app.planner.SleepScheduleStore
import com.waypoint.app.signal.WorkScheduleSignals
import kotlinx.coroutines.flow.MutableStateFlow

class SleepScheduleScript(
    private val store: SleepScheduleStore,
    private val registry: EventPlannerRegistry,
    private val ws: WorkScheduleSignals,
    private val sleepRefresh: MutableStateFlow<Int>
) : AppScript {

    override val id = "built_in.sleep_schedule"
    override val displayName = "Sleep Schedule"
    override val hasWidget = true
    override val canResetToDefaults = true

    override suspend fun resetToDefaults() {
        store.resetToDefaults()
        store.syncToRegistry(registry, ws)
    }

    @Composable
    override fun SettingsContent() {
        val refreshKey by sleepRefresh.collectAsState()
        SleepScheduleCard(store = store, registry = registry, ws = ws, refreshKey = refreshKey)
    }

    @Composable
    override fun WidgetContent(state: ScriptState?, onStateChange: (ScriptState) -> Unit) {
        val refreshKey by sleepRefresh.collectAsState()
        SleepScheduleCard(store = store, registry = registry, ws = ws, refreshKey = refreshKey)
    }
}
