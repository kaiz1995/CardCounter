package com.example.cardcounter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandPanelStatusTest {
    private fun counts(vararg pairs: Pair<Rank, Int>) = RankCounts.of(mapOf(*pairs))

    private fun newState(): GameState = GameReducer.newSession()

    private fun confirmed(cards: RankCounts): GameState {
        val result = GameReducer.apply(newState(), GameEvent.HandConfirmed(cards, cards.total()))
        return (result as DispatchResult.Accepted).state
    }

    @Test
    fun editingShowsDraftAndMarksHandUnconfirmed() {
        val draft = HandDraft().refresh(counts(Rank.ACE to 2), 2)
        val status = handPanelStatus(newState(), draft, rejectReason = null, skipped = false)

        assertEquals(HandPanelMode.EDITING, status.mode)
        assertTrue(status.handUnconfirmed)
        assertTrue(status.canEdit)
        assertTrue(status.canConfirm)
        assertEquals(2, status.handTotal)
        assertEquals(2, status.positionCount)
        assertFalse(status.mismatch)
        assertNull(status.message)
    }

    @Test
    fun editingMismatchBlocksConfirmAndExplainsWhy() {
        val draft = HandDraft().refresh(counts(Rank.ACE to 2), 3)
        val status = handPanelStatus(newState(), draft, rejectReason = null, skipped = false)

        assertFalse(status.canConfirm)
        assertTrue(status.mismatch)
        assertEquals("点数合计 2 张，数出来 3 张，对不上；核对后可以点点数加减改正，改完就能确认", status.message)
    }

    /** 回归：改过点数、合计和数出来的张数对不上时，确认按钮可点，状态窗只给提示不拦。 */
    @Test
    fun editedMismatchStaysConfirmableAndShowsWarning() {
        val draft = HandDraft().refresh(counts(Rank.ACE to 2), 3).adjust(Rank.KING, +2)
        val status = handPanelStatus(newState(), draft, rejectReason = null, skipped = false)

        assertTrue(status.canConfirm)
        assertTrue(status.mismatch)
        assertEquals("数出来 3 张，和点数合计 4 张不一样，核对没错就直接确认", status.message)
    }

    @Test
    fun editedMismatchConfirmsIntoTheLedgerWithoutPositionCountMatch() {
        val draft = HandDraft().refresh(counts(Rank.ACE to 2), 3).adjust(Rank.KING, +2)
        val data = requireNotNull(draft.confirmData())
        val result = GameReducer.apply(newState(), GameEvent.HandConfirmed(data.cards, data.handCount))
        assertTrue(result is DispatchResult.Accepted)
        assertEquals(4, (result as DispatchResult.Accepted).state.selfHand?.total())
    }

    @Test
    fun rejectReasonStaysVisibleAndHandStaysEditable() {
        val draft = HandDraft().refresh(counts(Rank.ACE to 2), 2)
        val status = handPanelStatus(newState(), draft, rejectReason = "本局手牌已确认，不能修改", skipped = false)

        assertEquals(HandPanelMode.EDITING, status.mode)
        assertTrue(status.canEdit)
        assertEquals("本局手牌已确认，不能修改", status.message)
    }

    @Test
    fun confirmedHandIsReadOnlyAndShowsTheConfirmedCards() {
        val hand = counts(Rank.ACE to 2, Rank.KING to 1)
        val draft = HandDraft().refresh(counts(Rank.QUEEN to 9), 9)
        val status = handPanelStatus(confirmed(hand), draft, rejectReason = null, skipped = false)

        assertEquals(HandPanelMode.CONFIRMED, status.mode)
        assertFalse(status.handUnconfirmed)
        assertFalse(status.canEdit)
        assertFalse(status.canConfirm)
        assertEquals(hand, status.shownCounts)
        assertEquals(3, status.handTotal)
    }

    @Test
    fun pausedShowsPauseReasonAndStopsEditing() {
        val paused = (GameReducer.apply(newState(), GameEvent.SessionPaused(PauseReason.CONSERVATION_FAILURE))
            as DispatchResult.Accepted).state
        val draft = HandDraft().refresh(counts(Rank.ACE to 2), 2)
        val status = handPanelStatus(paused, draft, rejectReason = null, skipped = false)

        assertEquals(HandPanelMode.PAUSED, status.mode)
        assertFalse(status.canEdit)
        assertFalse(status.canConfirm)
        assertEquals("已停记：守恒失败", status.message)
        assertFalse(status.canRevertLastOutsidePlay)
        assertTrue("停记时手牌若还没确认，横条仍要标未确认", status.handUnconfirmed)

        val withPlay = (GameReducer.apply(newState(), GameEvent.OutsideCardsPlayed(counts(Rank.ACE to 2), OutsideSeat.TOP, "p"))
            as DispatchResult.Accepted).state
        val pausedWithPlay = (GameReducer.apply(withPlay, GameEvent.SessionPaused(PauseReason.CONSERVATION_FAILURE))
            as DispatchResult.Accepted).state
        assertTrue(handPanelStatus(pausedWithPlay, draft, rejectReason = null, skipped = false).canRevertLastOutsidePlay)
    }

    @Test
    fun openedMidGameIsSkippedEvenIfHandLooksFine() {
        val draft = HandDraft().refresh(counts(Rank.ACE to 2), 2)
        val status = handPanelStatus(newState(), draft, rejectReason = null, skipped = true)

        assertEquals(HandPanelMode.SKIPPED, status.mode)
        assertFalse(status.canEdit)
        assertFalse(status.canConfirm)
        assertFalse(status.handUnconfirmed)
    }
}
