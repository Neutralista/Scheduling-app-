package com.waypoint.app.planner

/** A tile's horizontal slot: lane [lane] of [lanes] equal-width lanes. */
internal data class LaneSlot(val lane: Int, val lanes: Int) {
    companion object { val FULL = LaneSlot(0, 1) }
}

/** One timeline tile to lay out: [key] identifies it, [startMs]..[endMs] is its time span. */
internal data class LaneItem<K>(val key: K, val startMs: Long, val endMs: Long)

/**
 * Lays overlapping tiles out side by side instead of drawing them over each other. Tiles that
 * overlap, directly or through a chain of overlaps, form a group; each tile takes the leftmost
 * lane free at its start, and every tile in a group gets the group's lane count as its width.
 * A tile is treated as lasting at least [minDurationMs], since short tiles are drawn taller
 * than their time span and would otherwise still cover the next one.
 */
internal fun <K> assignLanes(items: List<LaneItem<K>>, minDurationMs: Long = 0L): Map<K, LaneSlot> {
    val result = HashMap<K, LaneSlot>(items.size)
    val sorted = items.sortedWith(compareBy<LaneItem<K>>({ it.startMs }, { -it.endMs }))
    val group = mutableListOf<Pair<K, Int>>()
    val laneEnds = mutableListOf<Long>()
    var groupEnd = Long.MIN_VALUE

    fun closeGroup() {
        group.forEach { (key, lane) -> result[key] = LaneSlot(lane, laneEnds.size) }
        group.clear(); laneEnds.clear()
    }

    for (item in sorted) {
        val end = maxOf(item.endMs, item.startMs + minDurationMs)
        if (item.startMs >= groupEnd) closeGroup()
        val lane = laneEnds.indexOfFirst { it <= item.startMs }.takeIf { it >= 0 } ?: laneEnds.size.also { laneEnds += 0L }
        laneEnds[lane] = end
        group += item.key to lane
        groupEnd = maxOf(groupEnd, end)
    }
    closeGroup()
    return result
}
