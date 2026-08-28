package com.example.cardcounter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GameReducerTest {

    @Test
    fun fixedInventoryIs198() {
        assertEquals(198, Rank.initialInventory.total())
        assertEquals(6, Rank.initialInventory[Rank.THREE])
        assertEquals(8, Rank.initialInventory[Rank.FOUR])
    }

    @Test
    fun newSessionStartsPrePlayWithNoRemaining() {
        val state = GameReducer.newSession()
        assertEquals(SessionPhase.PRE_PLAY, state.phase)
        assertNull(state.outsideRemaining)
        assertEquals(RankCounts.zero(), state.selfPlayed)
        assertEquals(RankCounts.zero(), state.outsidePlayed)
    }

    @Test
    fun prePlayRejectsSelfAndOutsidePlays() {
        val fresh = GameReducer.newSession()
        assertThrows(IllegalArgumentException::class.java) {
            GameReducer.apply(
                fresh,
                GameEvent.SelfCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 1)))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GameReducer.apply(
                fresh,
                GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 1)))
            )
        }
    }

    @Test
    fun lockHandAcceptsAnyCountNotJust33() {
        // 35 张：A 16 + K 16 + Q 3
        val locked = GameReducer.apply(
            GameReducer.newSession(),
            GameEvent.SelfHandLocked(
                RankCounts.of(mapOf(Rank.ACE to 16, Rank.KING to 16, Rank.QUEEN to 3))
            )
        )
        assertEquals(SessionPhase.ACTIVE, locked.phase)
        assertEquals(35, locked.selfLockedHand?.total())
        assertEquals(163, locked.outsideRemaining?.total())
        assertEquals(0, locked.outsideRemaining?.get(Rank.ACE))
    }

    @Test
    fun lockHandAcceptsTwentyTwo() {
        val lock22 = GameReducer.apply(
            GameReducer.newSession(),
            GameEvent.SelfHandLocked(RankCounts.of(mapOf(Rank.ACE to 16, Rank.KING to 6)))
        )
        assertEquals(22, lock22.selfLockedHand?.total())
        assertEquals(176, lock22.outsideRemaining?.total())
    }

    @Test
    fun lockHandRejectsEmptyOrOutOfInventory() {
        assertThrows(IllegalArgumentException::class.java) {
            GameReducer.apply(
                GameReducer.newSession(),
                GameEvent.SelfHandLocked(RankCounts.zero())
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GameReducer.apply(
                GameReducer.newSession(),
                GameEvent.SelfHandLocked(RankCounts.of(mapOf(Rank.ACE to 17)))
            )
        }
    }

    @Test
    fun outsideRemainingIgnoresSelfAndOnlyTracksOutside() {
        val locked = lock(mapOf(Rank.ACE to 1))
        val afterSelf = GameReducer.apply(
            locked,
            GameEvent.SelfCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 1)))
        )
        assertEquals(15, afterSelf.outsideRemaining?.get(Rank.ACE))
        assertEquals(1, afterSelf.selfPlayed[Rank.ACE])
        assertEquals(1, afterSelf.selfPlayedCount)
    }

    @Test
    fun outsidePlayStillTracksAfterSelfPlay() {
        val locked = lock(mapOf(Rank.ACE to 1))
        val afterSelf = GameReducer.apply(
            locked,
            GameEvent.SelfCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 1)))
        )
        val afterOutside = GameReducer.apply(
            afterSelf,
            GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 2)))
        )
        assertEquals(13, afterOutside.outsideRemaining?.get(Rank.ACE))
        assertEquals(2, afterOutside.outsidePlayedCount)
        assertEquals(1, afterOutside.selfPlayedCount)
    }

    @Test
    fun selfPlaysAccumulateForAuditWithoutTouchingRemaining() {
        val locked = lock(mapOf(Rank.ACE to 5))
        val state = GameReducer.apply(
            GameReducer.apply(
                locked,
                GameEvent.SelfCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 1)))
            ),
            GameEvent.SelfCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 3)))
        )
        assertEquals(4, state.selfPlayed[Rank.ACE])
        assertEquals(4, state.selfPlayedCount)
        assertEquals(11, state.outsideRemaining?.get(Rank.ACE))
    }

    @Test
    fun rejectsSelfPlayBeyondLockedHandConservation() {
        val locked = lock(mapOf(Rank.ACE to 2))
        assertThrows(IllegalArgumentException::class.java) {
            GameReducer.apply(
                locked,
                GameEvent.SelfCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 3)))
            )
        }
    }

    @Test
    fun rejectsOutsidePlayBeyondRemainingConservationInsteadOfClamping() {
        val locked = lock(mapOf(Rank.ACE to 15))
        assertThrows(IllegalArgumentException::class.java) {
            GameReducer.apply(
                locked,
                GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 2)))
            )
        }
    }

    @Test
    fun pausedSessionRejectsAutomaticEvents() {
        val paused = GameReducer.apply(
            lock(mapOf(Rank.ACE to 1)),
            GameEvent.SessionPaused(PauseReason.ZONE_AMBIGUOUS)
        )
        assertThrows(IllegalArgumentException::class.java) {
            GameReducer.apply(
                paused,
                GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 1)))
            )
        }
    }

    @Test
    fun replayMatchesLiveState() {
        val events = listOf(
            GameEvent.NewSession("s"),
            GameEvent.SelfHandLocked(RankCounts.of(mapOf(Rank.ACE to 2)), "h"),
            GameEvent.SelfCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 1)), "self"),
            GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 1)), "p")
        )
        val state = GameReducer.replay(events)
        assertEquals(13, state.outsideRemaining?.get(Rank.ACE))
        assertEquals(events, state.events)
    }

    @Test
    fun legacyLockEventAppliesDuringReplay() {
        val events = listOf(
            GameEvent.NewSession("s"),
            GameEvent.SelfHandLocked(RankCounts.of(mapOf(Rank.ACE to 1)), "h"),
            GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 1)), "p")
        )
        val state = GameReducer.replay(events)
        assertEquals(SessionPhase.ACTIVE, state.phase)
        assertEquals(14, state.outsideRemaining?.get(Rank.ACE))
        assertEquals(events, state.events)
    }

    @Test
    fun legacyLocklessLogReplaysAsCorruptedPaused() {
        val events = listOf(
            GameEvent.NewSession("s"),
            GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.FIVE to 1)), "legacy")
        )
        val state = GameReducer.replay(events)
        assertEquals(SessionPhase.PAUSED, state.phase)
        assertEquals(PauseReason.CORRUPTED_SESSION, state.pauseReason)
    }

    @Test
    fun outsidePlaysAccumulateBySeatAndStillFeedGlobalRemaining() {
        val active = lock(mapOf(Rank.KING to 1))
        val afterTop = GameReducer.apply(
            active,
            GameEvent.OutsideCardsPlayed(
                RankCounts.of(mapOf(Rank.ACE to 2)),
                seat = OutsideSeat.TOP
            )
        )
        val state = GameReducer.apply(
            afterTop,
            GameEvent.OutsideCardsPlayed(
                RankCounts.of(mapOf(Rank.KING to 1)),
                seat = OutsideSeat.LEFT_TOP
            )
        )
        assertEquals(2, state.playedBy(OutsideSeat.TOP)[Rank.ACE])
        assertEquals(1, state.playedBy(OutsideSeat.LEFT_TOP)[Rank.KING])
        assertEquals(0, state.playedBy(OutsideSeat.RIGHT_TOP).total())
        assertEquals(14, state.outsideRemaining?.get(Rank.ACE))
        assertEquals(14, state.outsideRemaining?.get(Rank.KING))
    }

    @Test
    fun outsideCardCountIsIndependentFromRankClassification() {
        val state = GameReducer.apply(
            lock(mapOf(Rank.ACE to 1)),
            GameEvent.OutsideCardsPlayed(
                cards = RankCounts.of(mapOf(Rank.ACE to 1)),
                seat = OutsideSeat.TOP,
                cardCount = 3
            )
        )
        assertEquals(3, state.outsidePlayedCount)
        assertEquals(3, state.playedCountBy(OutsideSeat.TOP))
        assertEquals(1, state.outsidePlayed[Rank.ACE])
    }

    @Test
    fun revertingOutsidePlayAlsoRevertsItsSeatLedger() {
        val active = lock(mapOf(Rank.SEVEN to 2))
        val play = GameEvent.OutsideCardsPlayed(
            RankCounts.of(mapOf(Rank.SEVEN to 2)),
            id = "seat-play",
            seat = OutsideSeat.RIGHT_BOTTOM
        )
        val played = GameReducer.apply(active, play)
        val reverted = GameReducer.apply(played, GameEvent.Reverted(play.id))
        assertEquals(0, reverted.playedBy(OutsideSeat.RIGHT_BOTTOM).total())
        assertEquals(active.outsideRemaining, reverted.outsideRemaining)
    }

    private fun lock(hand: Map<Rank, Int>): GameState = GameReducer.apply(
        GameReducer.newSession(),
        GameEvent.SelfHandLocked(RankCounts.of(hand))
    )
}
