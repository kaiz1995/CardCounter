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
    private val played = GameEvent.OutsideCardsPlayed(
        RankCounts.of(mapOf(Rank.KING to 1)),
        seat = OutsideSeat.TOP
    )

    @Test
    fun migratesOldActiveSessionToEmptyActiveOnce() {
        val active = GameReducer.apply(GameReducer.newSession(), played)

        val migrated = positiveCountSessionBootstrap(markerPresent = false, stored = active)

        assertTrue(migrated.migrated)
        assertEquals(SessionPhase.ACTIVE, migrated.state.phase)
        assertEquals(0, migrated.state.outsidePlayedCount)
        assertEquals(0, OutsideSeat.entries.sumOf { migrated.state.playedCountBy(it) })
    }

    @Test
    fun preservesExistingSessionWhenMigrationMarkerExists() {
        val active = GameReducer.apply(GameReducer.newSession(), played)

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
    fun appliesAgainstCurrentPersistedStateRatherThanStaleRepositorySnapshot() {
        val active = GameReducer.newSession()
        val staleA = active
        val playB = GameEvent.OutsideCardsPlayed(
            RankCounts.of(mapOf(Rank.KING to 1)), "B", OutsideSeat.TOP, cardCount = 1
        )
        val playA = GameEvent.OutsideCardsPlayed(
            RankCounts.of(mapOf(Rank.QUEEN to 1)), "A", OutsideSeat.LEFT_TOP, cardCount = 1
        )

        val afterB = applyPersistedEvent(active, playB)
        val afterA = applyPersistedEvent(afterB, playA)

        assertEquals(active, staleA)
        assertEquals(2, afterA.outsidePlayedCount)
        assertTrue(afterA.events.any { it.id == "A" })
        assertTrue(afterA.events.any { it.id == "B" })
    }

    @Test
    fun newSessionReplacementDropsPriorEvents() {
        val active = GameReducer.apply(GameReducer.newSession(), played)

        val replacement = GameReducer.newSession()

        assertEquals(SessionPhase.ACTIVE, active.phase)
        assertEquals(SessionPhase.ACTIVE, replacement.phase)
        assertEquals(1, replacement.events.size)
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
