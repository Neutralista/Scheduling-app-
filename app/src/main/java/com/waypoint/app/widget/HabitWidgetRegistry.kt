package com.waypoint.app.widget

/**
 * The entire "framework." Ships empty — it has no built-in habits and no
 * knowledge of what a habit is beyond "implements HabitWidget." To add a
 * habit type:
 *   1. Write a class implementing HabitWidget.
 *   2. Call register() with it from WaypointApplication.onCreate().
 *
 * register() calls onAttached() once, passing the shared SignalSources
 * instance — this is the only place that instance gets threaded through.
 */
object HabitWidgetRegistry {
    private val widgets = mutableMapOf<String, HabitWidget>()

    fun register(widget: HabitWidget, signals: SignalSources) {
        widget.onAttached(signals)
        widgets[widget.id] = widget
    }

    fun all(): List<HabitWidget> = widgets.values.toList()

    fun get(id: String): HabitWidget? = widgets[id]

    fun unregister(id: String) {
        widgets.remove(id)
    }
}
