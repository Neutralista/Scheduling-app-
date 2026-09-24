package com.waypoint.app.planner

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineLanesTest {

    @Test
    fun separateTiles_keepFullWidth() {
        val lanes = assignLanes(listOf(LaneItem("a", 0, 10), LaneItem("b", 10, 20)))
        assertEquals(LaneSlot.FULL, lanes["a"])
        assertEquals(LaneSlot.FULL, lanes["b"])
    }

    @Test
    fun overlappingTiles_sitSideBySide() {
        val lanes = assignLanes(listOf(LaneItem("meeting", 0, 60), LaneItem("notes", 0, 30), LaneItem("reply", 30, 60)))
        assertEquals(LaneSlot(0, 2), lanes["meeting"])
        assertEquals(LaneSlot(1, 2), lanes["notes"])
        // Reuses the lane "notes" freed.
        assertEquals(LaneSlot(1, 2), lanes["reply"])
    }

    @Test
    fun chainedOverlaps_shareOneWidth() {
        // a overlaps b, b overlaps c; a and c don't touch but are in the same group.
        val lanes = assignLanes(listOf(LaneItem("a", 0, 30), LaneItem("b", 20, 50), LaneItem("c", 40, 70)))
        assertEquals(LaneSlot(0, 2), lanes["a"])
        assertEquals(LaneSlot(1, 2), lanes["b"])
        assertEquals(LaneSlot(0, 2), lanes["c"])
    }

    @Test
    fun shortTiles_countTheirMinimumDrawnLength() {
        // 5 minutes long but drawn 20 minutes tall, so it would cover "next".
        val lanes = assignLanes(listOf(LaneItem("short", 0, 5), LaneItem("next", 10, 40)), minDurationMs = 20)
        assertEquals(LaneSlot(0, 2), lanes["short"])
        assertEquals(LaneSlot(1, 2), lanes["next"])
    }
}
