package com.waypoint.app.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PinnedBySessionsTest {

    private val today = LocalDate.now()
    private fun floating(id: String) = NamedBlockInstance(NamedBlock(id = id, name = id, isFloating = true), 0L, 0L)
    private fun session(id: String, date: LocalDate = today) =
        ActiveBlockSession(id, id, null, startedAtMs = 1_000L, scheduledEndMs = 5_000L, date = date.toString())

    @Test
    fun runningFloatingBlock_isPinnedAtItsSessionTimes() {
        val (pinned, floating) = listOf(floating("gym"), floating("read"))
            .pinnedBySessions(today, session("gym"), null)
        assertEquals(listOf("gym"), pinned.map { it.block.id })
        assertEquals(1_000L, pinned.single().scheduledStartMs)
        assertEquals(5_000L, pinned.single().estimatedEndMs)
        assertEquals(listOf("read"), floating.map { it.block.id })
    }

    @Test
    fun otherDays_areLeftToThePlanner() {
        val (pinned, floating) = listOf(floating("gym"))
            .pinnedBySessions(today.plusDays(1), session("gym"), null)
        assertTrue(pinned.isEmpty())
        assertEquals(1, floating.size)
    }

    @Test
    fun staleSession_doesNotPin() {
        val (pinned, _) = listOf(floating("gym"))
            .pinnedBySessions(today, session("gym", today.minusDays(1)), null)
        assertTrue(pinned.isEmpty())
    }
}
