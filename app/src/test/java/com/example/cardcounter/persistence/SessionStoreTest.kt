package com.example.cardcounter.persistence

import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.OutsideSeat
import com.example.cardcounter.domain.PauseReason
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.domain.SessionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStoreTest {
    @Test
    fun migratesOldActiveSessionToEmptyPrePlayOnce() {
        val active = GameReducer.apply(
            GameReducer.apply(
                GameReducer.newSession(),
                GameEvent.SelfHandLocked(RankCounts.of(mapOf(Rank.ACE to 1)))
            ),
            GameEvent.OutsideCardsPlayed(
                RankCounts.of(mapOf(Rank.KING to 1)),
                seat = OutsideSeat.TOP
            )
        )

        val migrated = positiveCountSessionBootstrap(markerPresent = false, stored = active)

        assertTrue(migrated.migrated)
        assertEquals(SessionPhase.PRE_PLAY, migrated.state.phase)
        assertEquals(0, migrated.state.outsidePlayedCount)
        assertEquals(0, OutsideSeat.entries.sumOf { migrated.state.playedCountBy(it) })
    }

    @Test
    fun preservesExistingSessionWhenMigrationMarkerExists() {
        val active = GameReducer.apply(
            GameReducer.apply(
                GameReducer.newSession(),
                GameEvent.SelfHandLocked(RankCounts.of(mapOf(Rank.ACE to 1)))
            ),
            GameEvent.OutsideCardsPlayed(
                RankCounts.of(mapOf(Rank.KING to 1)),
                seat = OutsideSeat.TOP
            )
        )

        val restored = positiveCountSessionBootstrap(markerPresent = true, stored = active)

        assertFalse(restored.migrated)
        assertEquals(active, restored.state)
        assertEquals(1, restored.state.playedCountBy(OutsideSeat.TOP))
    }

    @Test
    fun doesNotRepeatMigrationForExistingNewSession() {
        val fresh = GameReducer.newSession()

        val restored = positiveCountSessionBootstrap(markerPresent = true, stored = fresh)

        assertFalse(restored.migrated)
        assertEquals(fresh, restored.state)
    }

    @Test
    fun removesOnlyTrailingRecoverablePause() {
        val start = GameEvent.NewSession("start")
        val pause = GameEvent.SessionPaused(PauseReason.RANK_UNSTABLE, "pause")
        val baselinePause = GameEvent.SessionPaused(PauseReason.BASELINE_FAILURE, "baseline")

        assertEquals(listOf(start), listOf(start, pause).withoutRecoverablePause())
        assertEquals(listOf(start), listOf(start, baselinePause).withoutRecoverablePause())
        assertEquals(listOf(start), listOf(start).withoutRecoverablePause())
    }
}
