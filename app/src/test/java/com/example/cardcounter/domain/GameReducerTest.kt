package com.example.cardcounter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    /** 常规路径辅助：只关心结果状态；需要区分派发语义的用例直接调 GameReducer.apply。 */
    private fun apply(state: GameState, event: GameEvent): GameState =
        GameReducer.apply(state, event).state

    private fun activeHand(): GameState = apply(GameReducer.newSession(), GameEvent.SelfHandLocked(hand))

    @Test
    fun fixedInventoryIs198() {
        assertEquals(198, Rank.initialInventory.total())
        assertEquals(6, Rank.initialInventory[Rank.THREE])
        assertEquals(8, Rank.initialInventory[Rank.FOUR])
    }

    @Test
    fun outsideRemainingSubtractsSelfOnlyOnce() {
        val state = activeHand()
        val next = apply(
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
            apply(GameReducer.newSession(), GameEvent.SelfHandLocked(hand35)).selfLockedHand
        )
        // 空基线属于程序级误用，仍然抛异常。
        assertThrows(IllegalArgumentException::class.java) {
            GameReducer.apply(
                GameReducer.newSession(),
                GameEvent.SelfHandLocked(RankCounts.zero())
            )
        }
    }

    @Test
    fun selfPlayAccumulatesPositiveLedgerAndDerivesRemaining() {
        val state = activeHand()
        val next = apply(
            state,
            GameEvent.SelfCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 1, Rank.KING to 2)))
        )
        assertEquals(3, next.selfPlayedCount)
        assertEquals(hand.total() - 3, next.selfRemainingCount)
    }

    @Test
    fun pausedSessionRejectsAutomaticEvents() {
        val paused = apply(activeHand(), GameEvent.SessionPaused(PauseReason.ZONE_AMBIGUOUS))
        // phase 非法属于程序级错误，仍然抛异常。
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
        val active = activeHand()
        val topPlay = RankCounts.of(mapOf(Rank.ACE to 2))
        val leftPlay = RankCounts.of(mapOf(Rank.KING to 1))
        val afterTop = apply(active, GameEvent.OutsideCardsPlayed(topPlay, seat = OutsideSeat.TOP))
        val state = apply(
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
        val active = activeHand()
        val play = GameEvent.OutsideCardsPlayed(
            RankCounts.of(mapOf(Rank.SEVEN to 2)),
            id = "seat-play",
            seat = OutsideSeat.RIGHT_BOTTOM
        )
        val played = apply(active, play)
        val reverted = apply(played, GameEvent.Reverted(play.id))

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
        val active = activeHand()
        val played = apply(
            active,
            GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 1)), seat = OutsideSeat.TOP)
        )

        val fresh = apply(played, GameEvent.NewSession("fresh"))

        assertEquals(SessionPhase.PRE_PLAY, fresh.phase)
        assertEquals(0, fresh.outsidePlayedCount)
        assertEquals(0, fresh.selfPlayedCount)
        // 新局重置完整账本语义。
        assertTrue(fresh.rankLedgerComplete)
    }

    @Test
    fun quantityMismatchWithCompleteRanksIsRejected() {
        val active = activeHand()
        val event = GameEvent.OutsideCardsPlayed(
            RankCounts.of(mapOf(Rank.ACE to 2)),
            id = "mismatch",
            cardCount = 3
        )
        val result = GameReducer.apply(active, event)

        val rejected = result as? DispatchResult.Rejected ?: error("应为 Rejected，实际 $result")
        assertTrue(rejected.reason.contains("cardCount=3"))
        assertTrue(rejected.reason.contains("cards.total()=2"))
        // 账本不变：不扣数量，不进事件日志。
        assertEquals(active, rejected.state)
    }

    @Test
    fun selfQuantityMismatchWithCompleteRanksIsRejected() {
        val active = activeHand()
        val event = GameEvent.SelfCardsPlayed(
            RankCounts.of(mapOf(Rank.ACE to 1)),
            id = "self-mismatch",
            cardCount = 2
        )
        val result = GameReducer.apply(active, event)

        assertTrue(result is DispatchResult.Rejected)
        assertEquals(active, result.state)
    }

    @Test
    fun singleRankOverInventoryPausesWithConservationFailure() {
        // 手牌锁定 3 张 A，外面再出 14 张 A 超过 16 - 3 = 13 的可出上限。
        val active = activeHand()
        val play = GameEvent.OutsideCardsPlayed(
            RankCounts.of(mapOf(Rank.ACE to 14)),
            id = "over-ace",
            seat = OutsideSeat.TOP
        )
        val result = GameReducer.apply(active, play)

        val paused = result as? DispatchResult.Paused ?: error("应为 Paused，实际 $result")
        assertEquals(PauseReason.CONSERVATION_FAILURE, paused.reason)
        val state = paused.state
        assertEquals(SessionPhase.PAUSED, state.phase)
        assertEquals(0, state.outsidePlayed[Rank.ACE])
        // 原出牌事件不进 events，只追加 SessionPaused。
        assertTrue(state.events.none { it.id == "over-ace" })
        assertTrue(state.events.last() is GameEvent.SessionPaused)
        assertEquals(
            PauseReason.CONSERVATION_FAILURE,
            (state.events.last() as GameEvent.SessionPaused).reason
        )
    }

    @Test
    fun duplicateEventIsIgnoredWithoutDoubleDeduction() {
        val active = activeHand()
        val play = GameEvent.OutsideCardsPlayed(
            RankCounts.of(mapOf(Rank.ACE to 2)),
            id = "dup"
        )
        val once = apply(active, play)
        val again = GameReducer.apply(once, play.copy())

        assertTrue(again is DispatchResult.DuplicateIgnored)
        // 不重复扣减。
        assertEquals(once, again.state)
    }

    @Test
    fun conservationFailurePausesWithoutAppendingOriginalEvent() {
        // 总量守恒失败：锁定 198 张后外面再出任何牌都会超出。
        val locked = apply(
            GameReducer.newSession(),
            GameEvent.SelfHandLocked(RankCounts.zero(), cardCount = 198)
        )
        val play = GameEvent.OutsideCardsPlayed(
            RankCounts.of(mapOf(Rank.ACE to 1)),
            id = "boom",
            seat = OutsideSeat.TOP
        )
        val result = GameReducer.apply(locked, play)

        val paused = result as? DispatchResult.Paused ?: error("应为 Paused，实际 $result")
        assertEquals(PauseReason.CONSERVATION_FAILURE, paused.reason)
        assertEquals(SessionPhase.PAUSED, result.state.phase)
        assertEquals(0, result.state.outsidePlayedCount)
        assertTrue(result.state.events.none { it.id == "boom" })
    }

    @Test
    fun partialPlayEntersQuantityLedgerOnlyAndKillsRankLedger() {
        val active = activeHand()
        val event = GameEvent.OutsideCardsPlayed(
            RankCounts.of(mapOf(Rank.BIG_JOKER to 1)),
            seat = OutsideSeat.TOP,
            cardCount = 3,
            rankCompleteness = RankCompleteness.PARTIAL
        )
        val result = GameReducer.apply(active, event)

        assertTrue(result is DispatchResult.AcceptedQuantityOnly)
        val state = result.state
        // 数量账正确累加，Rank 账本未动。
        assertEquals(3, state.outsidePlayedCount)
        assertEquals(3, state.playedCountBy(OutsideSeat.TOP))
        assertEquals(0, state.playedBy(OutsideSeat.TOP).total())
        assertFalse(state.rankLedgerComplete)
        // Rank 未知时剩余数必须不可用。
        assertNull(state.outsideRemaining)
    }

    @Test
    fun unknownSelfLockUsesQuantityOnlyLedger() {
        val lock = GameEvent.SelfHandLocked(
            RankCounts.zero(),
            id = "lock-quantity",
            cardCount = 17,
            rankCompleteness = RankCompleteness.UNKNOWN
        )
        val result = GameReducer.apply(GameReducer.newSession(), lock)

        assertTrue(result is DispatchResult.Accepted)
        val state = result.state
        assertNull(state.selfLockedHand)
        assertEquals(17, state.selfLockedHandCount)
        assertFalse(state.rankLedgerComplete)
        assertNull(state.outsideRemaining)

        val next = GameReducer.apply(
            state,
            GameEvent.OutsideCardsPlayed(
                RankCounts.zero(), cardCount = 4, rankCompleteness = RankCompleteness.UNKNOWN
            )
        )
        assertTrue(next is DispatchResult.AcceptedQuantityOnly)
        assertEquals(4, next.state.outsidePlayedCount)
        assertEquals(0, next.state.outsidePlayed.total())
        assertNull(next.state.outsideRemaining)
    }

    @Test
    fun unknownSelfLockWithNonZeroCardsIsRejected() {
        val lock = GameEvent.SelfHandLocked(
            RankCounts.of(mapOf(Rank.ACE to 2)),
            id = "lock-bad",
            cardCount = 5,
            rankCompleteness = RankCompleteness.UNKNOWN
        )
        val result = GameReducer.apply(GameReducer.newSession(), lock)

        assertTrue(result is DispatchResult.Rejected)
        assertEquals(SessionPhase.PRE_PLAY, result.state.phase)
    }

    @Test
    fun seatCountsSumWithinGlobalAndEqualWhenComplete() {
        // 混合场景：PARTIAL 事件有座位但 Rank 未知，座位数量累计仍不超过全局。
        val active = activeHand()
        val mixed = apply(
            active,
            GameEvent.OutsideCardsPlayed(
                RankCounts.of(mapOf(Rank.ACE to 2)), "p1", OutsideSeat.TOP, 2
            )
        )
        val state = apply(
            mixed,
            GameEvent.OutsideCardsPlayed(
                RankCounts.zero(), "p2", OutsideSeat.LEFT_TOP, 4,
                RankCompleteness.UNKNOWN
            )
        )
        val seatSum = OutsideSeat.entries.sumOf { state.playedCountBy(it) }
        assertEquals(2, state.playedCountBy(OutsideSeat.TOP))
        assertEquals(4, state.playedCountBy(OutsideSeat.LEFT_TOP))
        assertEquals(6, state.outsidePlayedCount)
        assertTrue(seatSum <= state.outsidePlayedCount)

        // 全 COMPLETE 场景：座位累计与全局累计相等。
        val allComplete = apply(
            mixed,
            GameEvent.OutsideCardsPlayed(
                RankCounts.of(mapOf(Rank.KING to 4)), "p3", OutsideSeat.LEFT_TOP, 4
            )
        )
        assertEquals(
            allComplete.outsidePlayedCount,
            OutsideSeat.entries.sumOf { allComplete.playedCountBy(it) }
        )
    }

    @Test
    fun replaySkipsRejectedDuplicateAndIllegalEvents() {
        val events = listOf(
            GameEvent.NewSession("s"),
            GameEvent.SelfHandLocked(hand, "h"),
            GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 2)), "p1"),
            GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 2)), "p1"),
            GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 2)), "p2", cardCount = 5),
            GameEvent.SessionPaused(PauseReason.ZONE_AMBIGUOUS, "pause"),
            GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 1)), "p3")
        )
        val replayed = GameReducer.replay(events)

        // 重复与拒绝类事件跳过；暂停后的自动出牌因 phase 非法也跳过，不 corrupted。
        assertEquals(listOf("s", "h", "p1", "pause"), replayed.events.map { it.id })
        assertEquals(2, replayed.outsidePlayed[Rank.ACE])
        assertEquals(2, replayed.outsidePlayedCount)
        assertEquals(SessionPhase.PAUSED, replayed.phase)
    }

    @Test
    fun replayedLiveStateMatchesSequentialApply() {
        val events = listOf(
            GameEvent.NewSession("s"),
            GameEvent.SelfHandLocked(hand, "h"),
            GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 2)), "p1", OutsideSeat.TOP),
            GameEvent.SelfCardsPlayed(RankCounts.of(mapOf(Rank.KING to 1)), "sp")
        )
        var live = GameReducer.newSession(events.first() as GameEvent.NewSession)
        events.drop(1).forEach { event -> live = GameReducer.apply(live, event).state }
        assertEquals(live, GameReducer.replay(events))
    }

    @Test
    fun revertingQuantityOnlyEventRestoresCountLedgerOnly() {
        val active = activeHand()
        val play = GameEvent.OutsideCardsPlayed(
            RankCounts.zero(), "qty", OutsideSeat.TOP, 3, RankCompleteness.UNKNOWN
        )
        val played = apply(active, play)
        assertTrue(played.outsidePlayedCount == 3 && played.rankLedgerComplete.not())

        val reverted = apply(played, GameEvent.Reverted("qty"))

        assertEquals(0, reverted.outsidePlayedCount)
        assertEquals(0, reverted.playedCountBy(OutsideSeat.TOP))
        // 数量账降级是永久的：撤销也不恢复 outsideRemaining。
        assertFalse(reverted.rankLedgerComplete)
        assertNull(reverted.outsideRemaining)
    }
}
