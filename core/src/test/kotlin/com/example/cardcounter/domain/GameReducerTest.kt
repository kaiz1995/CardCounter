package com.example.cardcounter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GameReducerTest {
    /** 33 张手牌，点数向量完整。 */
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

    private fun counts(vararg pairs: Pair<Rank, Int>) = RankCounts.of(mapOf(*pairs))

    /** 常规路径辅助：只关心结果状态；需要区分派发语义的用例直接调 GameReducer.apply。 */
    private fun apply(state: GameState, event: GameEvent): GameState =
        GameReducer.apply(state, event).state

    private fun confirmed(): GameState =
        apply(GameReducer.newSession(), GameEvent.HandConfirmed(hand, hand.total(), "hand"))

    @Test
    fun fixedInventoryIs198() {
        assertEquals(198, Rank.initialInventory.total())
        assertEquals(6, Rank.initialInventory[Rank.THREE])
        assertEquals(8, Rank.initialInventory[Rank.FOUR])
    }

    @Test
    fun newSessionIsActiveAndHandUnconfirmed() {
        val state = GameReducer.newSession()

        assertEquals(SessionPhase.ACTIVE, state.phase)
        assertFalse(state.handConfirmed)
        assertNull(state.selfHand)
        assertEquals(Rank.initialInventory, state.outsideRemaining)
    }

    @Test
    fun outsidePlayBeforeConfirmIsRecordedAndRemainingDoesNotSubtractSelf() {
        val state = apply(
            GameReducer.newSession(),
            GameEvent.OutsideCardsPlayed(counts(Rank.ACE to 2), OutsideSeat.TOP, "p")
        )

        assertFalse(state.handConfirmed)
        assertEquals(2, state.outsidePlayed[Rank.ACE])
        // 未确认：只扣外面出牌，不扣自己的牌。
        assertEquals(14, state.outsideRemaining[Rank.ACE])
        assertEquals(6, state.outsideRemaining[Rank.THREE])
    }

    @Test
    fun confirmingHandAfterOutsidePlaysSubtractsSelfRetroactively() {
        val played = apply(
            GameReducer.newSession(),
            GameEvent.OutsideCardsPlayed(counts(Rank.ACE to 2), OutsideSeat.TOP, "p")
        )
        val state = apply(played, GameEvent.HandConfirmed(hand, hand.total(), "hand"))

        assertTrue(state.handConfirmed)
        assertEquals(hand, state.selfHand)
        // 16 - 自己 3 - 外面 2
        assertEquals(11, state.outsideRemaining[Rank.ACE])
        assertEquals(5, state.outsideRemaining[Rank.THREE])
        assertEquals(SessionPhase.ACTIVE, state.phase)
    }

    @Test
    fun outsideRemainingAfterConfirmSubtractsSelfOnlyOnce() {
        val next = apply(
            confirmed(),
            GameEvent.OutsideCardsPlayed(counts(Rank.ACE to 2), OutsideSeat.TOP, "p")
        )

        assertEquals(11, next.outsideRemaining[Rank.ACE])
        assertEquals(5, next.outsideRemaining[Rank.THREE])
    }

    @Test
    fun handCountMismatchIsRejectedAndStateUnchanged() {
        val before = GameReducer.newSession()
        val result = GameReducer.apply(before, GameEvent.HandConfirmed(hand, 30, "bad"))

        val rejected = result as? DispatchResult.Rejected ?: error("应为 Rejected，实际 $result")
        assertTrue(rejected.reason.contains("33"))
        assertTrue(rejected.reason.contains("30"))
        assertEquals(before, rejected.state)
        assertFalse(rejected.state.handConfirmed)
    }

    @Test
    fun emptyHandAndOverInventoryHandAreRejected() {
        val fresh = GameReducer.newSession()

        val empty = GameReducer.apply(fresh, GameEvent.HandConfirmed(RankCounts.zero(), 0, "empty"))
        assertTrue(empty is DispatchResult.Rejected)
        assertEquals(fresh, empty.state)

        val tooManyThrees = counts(Rank.THREE to 7)
        val over = GameReducer.apply(fresh, GameEvent.HandConfirmed(tooManyThrees, 7, "over"))
        assertTrue(over is DispatchResult.Rejected)
        assertEquals(fresh, over.state)
    }

    @Test
    fun secondConfirmIsRejectedWithFixedReason() {
        val first = confirmed()
        val other = counts(Rank.KING to 2)
        val result = GameReducer.apply(first, GameEvent.HandConfirmed(other, 2, "again"))

        val rejected = result as? DispatchResult.Rejected ?: error("应为 Rejected，实际 $result")
        assertEquals("本局手牌已确认，不能修改", rejected.reason)
        assertEquals(first, rejected.state)
        assertEquals(hand, rejected.state.selfHand)
    }

    @Test
    fun confirmExceedingInventoryWithRecordedOutsidePlaysPausesAndDoesNotEnterLedger() {
        // 外面已出 14 张 A；自己再确认 3 张 A → 17 > 16。
        val played = apply(
            GameReducer.newSession(),
            GameEvent.OutsideCardsPlayed(counts(Rank.ACE to 14), OutsideSeat.TOP, "p")
        )
        val result = GameReducer.apply(played, GameEvent.HandConfirmed(hand, hand.total(), "hand"))

        val paused = result as? DispatchResult.Paused ?: error("应为 Paused，实际 $result")
        assertEquals(PauseReason.CONSERVATION_FAILURE, paused.reason)
        val state = paused.state
        assertEquals(SessionPhase.PAUSED, state.phase)
        assertFalse(state.handConfirmed)
        assertTrue(state.events.none { it.id == "hand" })
        assertTrue(state.events.last() is GameEvent.SessionPaused)
        assertEquals(14, state.outsidePlayed[Rank.ACE])
    }

    @Test
    fun outsidePlayExceedingInventoryMinusSelfHandPausesAndPlayIsNotRecorded() {
        // 自己 3 张 A，外面再出 14 张 A 超过 16 - 3 = 13 的可出上限。
        val result = GameReducer.apply(
            confirmed(),
            GameEvent.OutsideCardsPlayed(counts(Rank.ACE to 14), OutsideSeat.TOP, "over-ace")
        )

        val paused = result as? DispatchResult.Paused ?: error("应为 Paused，实际 $result")
        assertEquals(PauseReason.CONSERVATION_FAILURE, paused.reason)
        val state = paused.state
        assertEquals(SessionPhase.PAUSED, state.phase)
        assertEquals(0, state.outsidePlayed[Rank.ACE])
        assertEquals(0, state.playedCountBy(OutsideSeat.TOP))
        assertTrue(state.events.none { it.id == "over-ace" })
        assertEquals(
            PauseReason.CONSERVATION_FAILURE,
            (state.events.last() as GameEvent.SessionPaused).reason
        )
    }

    @Test
    fun outsidePlayExceedingFullInventoryPausesEvenBeforeConfirm() {
        val result = GameReducer.apply(
            GameReducer.newSession(),
            GameEvent.OutsideCardsPlayed(counts(Rank.BIG_JOKER to 5), OutsideSeat.LEFT_TOP, "over")
        )

        assertTrue(result is DispatchResult.Paused)
        assertEquals(0, result.state.outsidePlayed.total())
        assertTrue(result.state.events.none { it.id == "over" })
    }

    @Test
    fun emptyOutsidePlayIsRejected() {
        val fresh = GameReducer.newSession()
        val result = GameReducer.apply(
            fresh,
            GameEvent.OutsideCardsPlayed(RankCounts.zero(), OutsideSeat.TOP, "empty")
        )

        assertTrue(result is DispatchResult.Rejected)
        assertEquals(fresh, result.state)
    }

    @Test
    fun seatPlayedCountEqualsItsRankVectorTotal() {
        var state = GameReducer.newSession()
        state = apply(state, GameEvent.OutsideCardsPlayed(counts(Rank.ACE to 2), OutsideSeat.TOP, "p1"))
        state = apply(state, GameEvent.OutsideCardsPlayed(counts(Rank.KING to 1, Rank.QUEEN to 3), OutsideSeat.TOP, "p2"))
        state = apply(state, GameEvent.OutsideCardsPlayed(counts(Rank.KING to 4), OutsideSeat.LEFT_TOP, "p3"))

        for (seat in OutsideSeat.entries) {
            assertEquals(state.playedBy(seat).total(), state.playedCountBy(seat))
        }
        assertEquals(6, state.playedCountBy(OutsideSeat.TOP))
        assertEquals(4, state.playedCountBy(OutsideSeat.LEFT_TOP))
        assertEquals(0, state.playedCountBy(OutsideSeat.RIGHT_BOTTOM))
        // 五家向量之和 == 外面累计；各家各自累计，不做合计字段。
        val seatSum = OutsideSeat.entries.fold(RankCounts.zero()) { sum, seat -> sum.plus(state.playedBy(seat)) }
        assertEquals(state.outsidePlayed, seatSum)
        assertEquals(10, state.outsidePlayedCount)
    }

    @Test
    fun revertingOutsidePlayRestoresRemainingAndSeatCount() {
        val active = confirmed()
        val play = GameEvent.OutsideCardsPlayed(counts(Rank.SEVEN to 2), OutsideSeat.RIGHT_BOTTOM, "seat-play")
        val played = apply(active, play)
        assertEquals(2, played.playedCountBy(OutsideSeat.RIGHT_BOTTOM))

        val reverted = apply(played, GameEvent.Reverted(play.id, "rv"))

        assertEquals(0, reverted.playedBy(OutsideSeat.RIGHT_BOTTOM).total())
        assertEquals(0, reverted.playedCountBy(OutsideSeat.RIGHT_BOTTOM))
        assertEquals(active.outsideRemaining, reverted.outsideRemaining)
        assertEquals(setOf(play.id), reverted.revertedEventIds)
    }

    @Test
    fun revertingHandConfirmedIsRejected() {
        val state = confirmed()
        val result = GameReducer.apply(state, GameEvent.Reverted("hand", "rv"))

        assertTrue(result is DispatchResult.Rejected)
        assertEquals(state, result.state)
        assertTrue(result.state.handConfirmed)
    }

    @Test
    fun revertingTwiceIsRejected() {
        val play = GameEvent.OutsideCardsPlayed(counts(Rank.ACE to 1), OutsideSeat.TOP, "p")
        val reverted = apply(apply(GameReducer.newSession(), play), GameEvent.Reverted("p", "rv1"))
        val again = GameReducer.apply(reverted, GameEvent.Reverted("p", "rv2"))

        assertTrue(again is DispatchResult.Rejected)
        assertEquals(reverted, again.state)
    }

    @Test
    fun pausedSessionRejectsAutomaticEventsAsProgrammingError() {
        val paused = apply(GameReducer.newSession(), GameEvent.SessionPaused(PauseReason.ZONE_AMBIGUOUS))
        // phase 非法属于程序级错误，仍然抛异常。
        assertThrows(IllegalArgumentException::class.java) {
            GameReducer.apply(paused, GameEvent.OutsideCardsPlayed(counts(Rank.ACE to 1), OutsideSeat.TOP))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GameReducer.apply(paused, GameEvent.HandConfirmed(hand, hand.total()))
        }
    }

    @Test
    fun duplicateEventIsIgnoredWithoutDoubleDeduction() {
        val play = GameEvent.OutsideCardsPlayed(counts(Rank.ACE to 2), OutsideSeat.TOP, "dup")
        val once = apply(confirmed(), play)
        val again = GameReducer.apply(once, play.copy())

        assertTrue(again is DispatchResult.DuplicateIgnored)
        assertEquals(once, again.state)
    }

    @Test
    fun newSessionDropsOldLedgersAndHandConfirmation() {
        val played = apply(
            confirmed(),
            GameEvent.OutsideCardsPlayed(counts(Rank.ACE to 1), OutsideSeat.TOP, "p")
        )

        val fresh = apply(played, GameEvent.NewSession("fresh"))

        assertEquals(SessionPhase.ACTIVE, fresh.phase)
        assertFalse(fresh.handConfirmed)
        assertEquals(0, fresh.outsidePlayedCount)
        assertEquals(Rank.initialInventory, fresh.outsideRemaining)
    }

    @Test
    fun replayMatchesSequentialApply() {
        val events = listOf(
            GameEvent.NewSession("s"),
            GameEvent.OutsideCardsPlayed(counts(Rank.KING to 1), OutsideSeat.LEFT_TOP, "p0"),
            GameEvent.HandConfirmed(hand, hand.total(), "h"),
            GameEvent.OutsideCardsPlayed(counts(Rank.ACE to 2), OutsideSeat.TOP, "p1"),
            GameEvent.OutsideCardsPlayed(counts(Rank.TEN to 2), OutsideSeat.TOP, "p2"),
            GameEvent.Reverted("p2", "rv")
        )
        var live = GameReducer.newSession(events.first() as GameEvent.NewSession)
        events.drop(1).forEach { event -> live = GameReducer.apply(live, event).state }

        val replayed = GameReducer.replay(events)

        assertEquals(live, replayed)
        assertEquals(events, replayed.events)
        assertEquals(11, replayed.outsideRemaining[Rank.ACE])
    }

    @Test
    fun replaySkipsRejectedDuplicateAndIllegalEvents() {
        val events = listOf(
            GameEvent.NewSession("s"),
            GameEvent.HandConfirmed(hand, hand.total(), "h"),
            GameEvent.OutsideCardsPlayed(counts(Rank.ACE to 2), OutsideSeat.TOP, "p1"),
            GameEvent.OutsideCardsPlayed(counts(Rank.ACE to 2), OutsideSeat.TOP, "p1"),
            GameEvent.HandConfirmed(counts(Rank.KING to 1), 1, "h2"),
            GameEvent.SessionPaused(PauseReason.ZONE_AMBIGUOUS, "pause"),
            GameEvent.OutsideCardsPlayed(counts(Rank.ACE to 1), OutsideSeat.TOP, "p3")
        )
        val replayed = GameReducer.replay(events)

        // 重复、已确认再确认被跳过；暂停后的出牌因 phase 非法也跳过，不 corrupted。
        assertEquals(listOf("s", "h", "p1", "pause"), replayed.events.map { it.id })
        assertEquals(2, replayed.outsidePlayed[Rank.ACE])
        assertEquals(hand, replayed.selfHand)
        assertEquals(SessionPhase.PAUSED, replayed.phase)
    }

    @Test
    fun replayWithoutLeadingNewSessionIsCorrupted() {
        val state = GameReducer.replay(listOf(GameEvent.SessionResumed("r")))

        assertEquals(SessionPhase.PAUSED, state.phase)
        assertEquals(PauseReason.CORRUPTED_SESSION, state.pauseReason)
    }
}
