package com.example.cardcounter.capture

import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.domain.SessionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfHandCountTrackerTest {
    private fun tracker() = SelfHandCountTracker(stableSamplesRequired = 2, suspectedNewGameIncrease = 10)

    @Test
    fun reboundDoesNotPauseActiveSession() {
        val tracker = tracker()
        tracker.reset(33)
        repeat(2) { tracker.observe(33) }

        val change = observeStable(tracker, 35)

        assertEquals(SelfHandCountChange.ReboundIgnored(33, 35, suspectedNewGame = false), change)
        val state = GameReducer.apply(
            GameReducer.newSession(),
            GameEvent.SelfHandLocked(RankCounts.of(mapOf(Rank.THREE to 33)))
        )
        assertEquals(SessionPhase.ACTIVE, state.phase)
        assertFalse(state.events.any { it is GameEvent.SessionPaused })
    }

    @Test
    fun observesDecreaseWithoutCreatingPlayEvent() {
        val tracker = tracker()
        tracker.reset(33)
        assertEquals(SelfHandCountChange.DecreaseObserved(1), observeStable(tracker, 32))
        assertTrue(observeStable(tracker, 33) is SelfHandCountChange.ReboundIgnored)
        assertEquals(SelfHandCountChange.DecreaseObserved(2), observeStable(tracker, 30))
    }

    @Test
    fun flagsOnlyLargeStableReboundAsSuspectedNewGame() {
        val tracker = tracker()
        tracker.reset(20)

        assertEquals(SelfHandCountChange.ReboundIgnored(20, 30, suspectedNewGame = true), observeStable(tracker, 30))
    }

    private fun observeStable(tracker: SelfHandCountTracker, count: Int): SelfHandCountChange? {
        tracker.observe(count)
        return tracker.observe(count)
    }
}
