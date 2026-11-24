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
        assertEquals("点数合计 2 张，数出来 3 张，必须改到一致才能确认", status.message)
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
        assertTrue("停记时手牌若还没确认，横条仍要标未确认", status.handUnconfirmed)
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
