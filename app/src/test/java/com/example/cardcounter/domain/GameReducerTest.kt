package com.example.cardcounter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun selfPlayAccumulatesPositiveLedgerAndDerivesRemaining() {
        val state = GameReducer.apply(
            GameReducer.newSession(),
            GameEvent.SelfHandLocked(hand)
        )
        val next = GameReducer.apply(
            state,
            GameEvent.SelfCardsPlayed(RankCounts.zero(), cardCount = 3)
        )
        assertEquals(3, next.selfPlayedCount)
        assertEquals(hand.total() - 3, next.selfRemainingCount)
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

    @Test
    fun newSessionDropsOldPositiveLedgers() {
        val active = GameReducer.apply(GameReducer.newSession(), GameEvent.SelfHandLocked(hand))
        val played = GameReducer.apply(
            active,
            GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 1)), seat = OutsideSeat.TOP)
        )

        val fresh = GameReducer.apply(played, GameEvent.NewSession("fresh"))

        assertEquals(SessionPhase.PRE_PLAY, fresh.phase)
        assertEquals(0, fresh.outsidePlayedCount)
        assertEquals(0, fresh.selfPlayedCount)
    }

    @Test
    fun wrongRankStillUsesReportedCardCount() {
        val active = GameReducer.apply(GameReducer.newSession(), GameEvent.SelfHandLocked(hand))
        val state = GameReducer.apply(
            active,
            GameEvent.OutsideCardsPlayed(
                RankCounts.of(mapOf(Rank.BIG_JOKER to 1)),
                seat = OutsideSeat.TOP,
                cardCount = 3
            )
        )

        assertEquals(3, state.outsidePlayedCount)
        assertEquals(3, state.playedCountBy(OutsideSeat.TOP))
        assertEquals(1, state.playedBy(OutsideSeat.TOP)[Rank.BIG_JOKER])
    }

    @Test
    fun conservationViolationPausesInsteadOfClampingRankGuess() {
        val locked = GameReducer.apply(
            GameReducer.newSession(),
            GameEvent.SelfHandLocked(RankCounts.zero(), cardCount = 198)
        )

        val state = GameReducer.apply(
            locked,
            GameEvent.OutsideCardsPlayed(
                RankCounts.of(mapOf(Rank.ACE to 1)),
                seat = OutsideSeat.TOP,
                cardCount = 1
            )
        )

        assertEquals(SessionPhase.PAUSED, state.phase)
        assertEquals(PauseReason.NEW_GAME_UNCLEAR, state.pauseReason)
        assertEquals(0, state.outsidePlayedCount)
    }

    /** 正常对局：外面累计出牌远低于 198 - 自己手牌，不应触发暂停。 */
    @Test
    fun normalOutsidePlaysDoNotTripConservation() {
        val active = GameReducer.apply(
            GameReducer.newSession(),
            GameEvent.SelfHandLocked(hand)
        )
        val state = GameReducer.apply(
            active,
            GameEvent.OutsideCardsPlayed(
                RankCounts.of(mapOf(Rank.ACE to 3)),
                seat = OutsideSeat.TOP,
                cardCount = 3
            )
        )

        assertEquals(SessionPhase.ACTIVE, state.phase)
        assertNull(state.pauseReason)
        assertEquals(3, state.outsidePlayedCount)
        assertEquals(hand.total(), state.selfRemainingCount)
    }

    /** 自己出牌不占用「外面出牌」的额度，不应让外面出牌提前触发暂停。 */
    @Test
    fun selfPlaysDoNotConsumeOutsideConservationBudget() {
        val active = GameReducer.apply(
            GameReducer.newSession(),
            GameEvent.SelfHandLocked(hand)
        )
        val selfPlayed = GameReducer.apply(
            active,
            GameEvent.SelfCardsPlayed(RankCounts.zero(), cardCount = 5)
        )
        val budget = Rank.initialInventory.total() - hand.total()
        val state = GameReducer.apply(
            selfPlayed,
            GameEvent.OutsideCardsPlayed(
                RankCounts.zero(),
                seat = OutsideSeat.LEFT_TOP,
                cardCount = budget - 1
            )
        )

        assertEquals(SessionPhase.ACTIVE, state.phase)
        assertEquals(budget - 1, state.outsidePlayedCount)
    }

    /**
     * 重复入账：外面出牌累计超过 198 - 自己锁定手牌，说明某座位把同一手牌记了两次。
     * 这必须暂停，而不是静默接受。
     */
    @Test
    fun duplicateOutsideBookingTripsConservationAndPauses() {
        val active = GameReducer.apply(
            GameReducer.newSession(),
            GameEvent.SelfHandLocked(hand)
        )
        val budget = Rank.initialInventory.total() - hand.total()
        val first = GameReducer.apply(
            active,
            GameEvent.OutsideCardsPlayed(
                RankCounts.zero(),
                seat = OutsideSeat.TOP,
                cardCount = budget
            )
        )
        assertEquals(SessionPhase.ACTIVE, first.phase)

        // 同上界再记一笔：物理上不可能，属于重复入账
        val violated = GameReducer.apply(
            first,
            GameEvent.OutsideCardsPlayed(
                RankCounts.zero(),
                seat = OutsideSeat.TOP,
                cardCount = 1
            )
        )

        assertEquals(SessionPhase.PAUSED, violated.phase)
        assertEquals(PauseReason.NEW_GAME_UNCLEAR, violated.pauseReason)
        // 未接受的这一笔不计入账
        assertEquals(budget, violated.outsidePlayedCount)
    }
}
