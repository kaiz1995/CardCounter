package com.example.cardcounter.domain

import org.junit.Assert.assertEquals
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
    fun newSessionStartsActiveWithFullRemaining() {
        val state = GameReducer.newSession()
        assertEquals(SessionPhase.ACTIVE, state.phase)
        assertEquals(Rank.initialInventory, state.outsideRemaining)
    }

    @Test
    fun outsideRemainingSubtractsSelfAndOutsideOnce() {
        val afterSelf = GameReducer.apply(
            GameReducer.newSession(),
            GameEvent.SelfCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 1)))
        )
        assertEquals(15, afterSelf.outsideRemaining[Rank.ACE])
        assertEquals(1, afterSelf.selfPlayed[Rank.ACE])
        val afterOutside = GameReducer.apply(
            afterSelf,
            GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 2)))
        )
        assertEquals(13, afterOutside.outsideRemaining[Rank.ACE])
        assertEquals(1, afterOutside.selfPlayedCount)
        assertEquals(2, afterOutside.outsidePlayedCount)
    }

    @Test
    fun selfPlaysAccumulatePositively() {
        val state = GameReducer.apply(
            GameReducer.apply(
                GameReducer.newSession(),
                GameEvent.SelfCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 1)))
            ),
            GameEvent.SelfCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 3)))
        )
        assertEquals(4, state.selfPlayed[Rank.ACE])
        assertEquals(4, state.selfPlayedCount)
        assertEquals(12, state.outsideRemaining[Rank.ACE])
    }

    @Test
    fun rejectsSelfPlayBeyondInventoryConservation() {
        assertThrows(IllegalArgumentException::class.java) {
            GameReducer.apply(
                GameReducer.newSession(),
                GameEvent.SelfCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 17)))
            )
        }
    }

    @Test
    fun rejectsOutsidePlayBeyondRemainingConservationInsteadOfClamping() {
        val afterSelf = GameReducer.apply(
            GameReducer.newSession(),
            GameEvent.SelfCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 15)))
        )
        assertThrows(IllegalArgumentException::class.java) {
            GameReducer.apply(
                afterSelf,
                GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 2)))
            )
        }
    }

    @Test
    fun pausedSessionRejectsAutomaticEvents() {
        val paused = GameReducer.apply(
            GameReducer.newSession(),
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
            GameEvent.SelfCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 1)), "self"),
            GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 2)), "p")
        )
        val state = GameReducer.replay(events)
        assertEquals(13, state.outsideRemaining[Rank.ACE])
        assertEquals(events, state.events)
    }

    @Test
    fun outsidePlaysAccumulateBySeatAndStillFeedGlobalRemaining() {
        val active = GameReducer.newSession()
        val topPlay = RankCounts.of(mapOf(Rank.ACE to 2))
        val leftPlay = RankCounts.of(mapOf(Rank.KING to 1))
        val afterTop = GameReducer.apply(
            active,
            GameEvent.OutsideCardsPlayed(topPlay, seat = OutsideSeat.TOP)
        )
        val state = GameReducer.apply(
            afterTop,
            GameEvent.OutsideCardsPlayed(leftPlay, seat = OutsideSeat.LEFT_TOP)
        )
        assertEquals(2, state.playedBy(OutsideSeat.TOP)[Rank.ACE])
        assertEquals(1, state.playedBy(OutsideSeat.LEFT_TOP)[Rank.KING])
        assertEquals(0, state.playedBy(OutsideSeat.RIGHT_TOP).total())
        assertEquals(14, state.outsideRemaining[Rank.ACE])
        assertEquals(15, state.outsideRemaining[Rank.KING])
    }

    @Test
    fun outsideCardCountIsIndependentFromRankClassification() {
        val state = GameReducer.apply(
            GameReducer.newSession(),
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
        val active = GameReducer.newSession()
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

    @Test
    fun legacyOutsidePlayKeepsGlobalCountWithoutInventingSeat() {
        val state = GameReducer.replay(
            listOf(
                GameEvent.NewSession("s"),
                GameEvent.OutsideCardsPlayed(
                    RankCounts.of(mapOf(Rank.FIVE to 1)),
                    "legacy"
                )
            )
        )
        assertEquals(1, state.outsidePlayed[Rank.FIVE])
        assertEquals(0, OutsideSeat.entries.sumOf { state.playedBy(it).total() })
    }

    @Test
    fun legacySelfHandLockedEventIsIgnoredDuringReplay() {
        val events = listOf(
            GameEvent.NewSession("s"),
            GameEvent.SelfHandLocked(RankCounts.of(mapOf(Rank.ACE to 1)), "h"),
            GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 1)), "p")
        )
        val state = GameReducer.replay(events)
        assertEquals(SessionPhase.ACTIVE, state.phase)
        assertEquals(15, state.outsideRemaining[Rank.ACE])
        assertEquals(listOf(events[0], events[2]), state.events)
    }
}
