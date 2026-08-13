package com.example.cardcounter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GameReducerTest {
    private val hand = RankCounts.of(
        mapOf(
            Rank.SMALL_JOKER to 2,
            Rank.ACE to 3,
            Rank.KING to 2,
            Rank.QUEEN to 2,
            Rank.JACK to 2,
            Rank.TEN to 5,
            Rank.NINE to 5,
            Rank.EIGHT to 3,
            Rank.SEVEN to 1,
            Rank.SIX to 3,
            Rank.FIVE to 2,
            Rank.FOUR to 2,
            Rank.THREE to 1
        )
    )

    @Test
    fun fixedInventoryIs198() {
        assertEquals(198, Rank.initialInventory.total())
        assertEquals(6, Rank.initialInventory[Rank.THREE])
        assertEquals(8, Rank.initialInventory[Rank.FOUR])
    }

    @Test
    fun outsideRemainingSubtractsSelfOnlyOnce() {
        val state = GameReducer.apply(
            GameReducer.newSession(),
            GameEvent.SelfHandLocked(hand)
        )
        val next = GameReducer.apply(
            state,
            GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 2)))
        )
        val remaining = next.outsideRemaining!!
        assertEquals(11, remaining[Rank.ACE])
        assertEquals(5, remaining[Rank.THREE])
    }

    @Test
    fun acceptsNon33CardBaselineAndRejectsEmptyBaseline() {
        val hand35 = RankCounts.of(mapOf(Rank.TEN to 16, Rank.NINE to 16, Rank.THREE to 3))
        assertEquals(
            hand35,
            GameReducer.apply(GameReducer.newSession(), GameEvent.SelfHandLocked(hand35)).selfLockedHand
        )
        assertThrows(IllegalArgumentException::class.java) {
            GameReducer.apply(
                GameReducer.newSession(),
                GameEvent.SelfHandLocked(RankCounts.zero())
            )
        }
    }

    @Test
    fun selfPlayDoesNotChangeOutsideRemaining() {
        val state = GameReducer.apply(
            GameReducer.newSession(),
            GameEvent.SelfHandLocked(hand)
        )
        val before = state.outsideRemaining
        val next = GameReducer.apply(
            state,
            GameEvent.SelfCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 1)))
        )
        assertEquals(before, next.outsideRemaining)
    }

    @Test
    fun pausedSessionRejectsAutomaticEvents() {
        val active = GameReducer.apply(
            GameReducer.newSession(),
            GameEvent.SelfHandLocked(hand)
        )
        val paused = GameReducer.apply(
            active,
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
            GameEvent.SelfHandLocked(hand, "h"),
            GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 2)), "p")
        )
        val state = GameReducer.replay(events)
        assertEquals(11, state.outsideRemaining!![Rank.ACE])
        assertEquals(events, state.events)
    }

    @Test
    fun outsidePlaysAccumulateBySeatAndStillFeedGlobalRemaining() {
        val active = GameReducer.apply(
            GameReducer.newSession(),
            GameEvent.SelfHandLocked(hand)
        )
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
        assertEquals(11, state.outsideRemaining!![Rank.ACE])
        assertEquals(13, state.outsideRemaining!![Rank.KING])
    }

    @Test
    fun preservesCardCountWhenRankIsExhausted() {
        val hand = RankCounts.of(mapOf(Rank.ACE to 16))
        val active = GameReducer.apply(GameReducer.newSession(), GameEvent.SelfHandLocked(hand))
        val next = GameReducer.apply(active, GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 3)), id = "exhausted", cardCount = 3))
        assertEquals(0, next.outsidePlayed[Rank.ACE])
        assertEquals(3, next.outsidePlayedCount)
    }

    @Test
    fun clampsRankButKeepsCountAndRevertsConsistently() {
        val hand = RankCounts.of(mapOf(Rank.ACE to 15))
        val active = GameReducer.apply(GameReducer.newSession(), GameEvent.SelfHandLocked(hand))
        val play = GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 3)), id = "partial", seat = OutsideSeat.TOP, cardCount = 3)
        val played = GameReducer.apply(active, play)
        assertEquals(1, played.playedBy(OutsideSeat.TOP)[Rank.ACE])
        assertEquals(3, played.playedCountBy(OutsideSeat.TOP))
        val replayed = GameReducer.replay(listOf(GameEvent.NewSession("s"), GameEvent.SelfHandLocked(hand, "h"), play))
        assertEquals(played.outsidePlayed, replayed.outsidePlayed)
        val reverted = GameReducer.apply(played, GameEvent.Reverted(play.id, "r"))
        assertEquals(0, reverted.outsidePlayed[Rank.ACE])
        assertEquals(0, reverted.outsidePlayedCount)
    }

    @Test
    fun outsideCardCountIsIndependentFromRankClassification() {
        val active = GameReducer.apply(
            GameReducer.newSession(),
            GameEvent.SelfHandLocked(hand)
        )
        val state = GameReducer.apply(
            active,
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
        val active = GameReducer.apply(
            GameReducer.newSession(),
            GameEvent.SelfHandLocked(hand)
        )
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
                GameEvent.SelfHandLocked(hand, "h"),
                GameEvent.OutsideCardsPlayed(
                    RankCounts.of(mapOf(Rank.FIVE to 1)),
                    "legacy"
                )
            )
        )

        assertEquals(1, state.outsidePlayed[Rank.FIVE])
        assertEquals(0, OutsideSeat.entries.sumOf { state.playedBy(it).total() })
    }
}
