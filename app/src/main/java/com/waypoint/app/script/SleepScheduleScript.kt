package com.waypoint.app.script

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import com.waypoint.app.planner.EventPlannerRegistry
import com.waypoint.app.planner.SleepLogCard
import com.waypoint.app.planner.SleepLogStore
import com.waypoint.app.planner.SleepScheduleCard
import com.waypoint.app.planner.SleepScheduleStore
import com.waypoint.app.signal.WorkScheduleSignals
import kotlinx.coroutines.flow.MutableStateFlow

class SleepScheduleScript(
    private val store: SleepScheduleStore,
    private val registry: EventPlannerRegistry,
    private val ws: WorkScheduleSignals,
    private val sleepRefresh: MutableStateFlow<Int>,
    private val logStore: SleepLogStore
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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SleepScheduleCard(store = store, registry = registry, ws = ws, refreshKey = refreshKey)
            SleepLogCard(logStore = logStore)
        }
    }

    @Composable
    override fun WidgetContent(state: ScriptState?, onStateChange: (ScriptState) -> Unit) {
        val refreshKey by sleepRefresh.collectAsState()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SleepScheduleCard(store = store, registry = registry, ws = ws, refreshKey = refreshKey)
            SleepLogCard(logStore = logStore)
        }
    }
}
