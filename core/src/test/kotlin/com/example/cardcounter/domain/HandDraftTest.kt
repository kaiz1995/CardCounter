package com.example.cardcounter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandDraftTest {
    private fun counts(vararg pairs: Pair<Rank, Int>) = RankCounts.of(mapOf(*pairs))

    @Test
    fun refreshFollowsRecognitionWhenNothingEdited() {
        val draft = HandDraft().refresh(counts(Rank.ACE to 2, Rank.KING to 1), 3)
        assertEquals(counts(Rank.ACE to 2, Rank.KING to 1), draft.counts)
        assertEquals(3, draft.positionCount)
        assertTrue(draft.edited.isEmpty())

        val next = draft.refresh(counts(Rank.ACE to 3, Rank.KING to 1), 4)
        assertEquals(counts(Rank.ACE to 3, Rank.KING to 1), next.counts)
        assertEquals(4, next.positionCount)
    }

    @Test
    fun adjustAddsAndRemovesOneCardAndMarksEdited() {
        val draft = HandDraft().refresh(counts(Rank.ACE to 2), 2)
        val plus = draft.adjust(Rank.ACE, +1)
        assertEquals(3, plus.counts[Rank.ACE])
        assertTrue(plus.isEdited(Rank.ACE))
        assertFalse(plus.isEdited(Rank.KING))

        val minus = plus.adjust(Rank.ACE, -1).adjust(Rank.ACE, -1)
        assertEquals(1, minus.counts[Rank.ACE])
    }

    @Test
    fun adjustStopsAtZeroAndAtInventory() {
        val empty = HandDraft()
        val stillZero = empty.adjust(Rank.ACE, -1)
        assertEquals(0, stillZero.counts[Rank.ACE])
        assertFalse("到下限没有变化，不算改过", stillZero.isEdited(Rank.ACE))

        val full = HandDraft().refresh(counts(Rank.THREE to 6), 6)
        val capped = full.adjust(Rank.THREE, +1)
        assertEquals(Rank.THREE.initialCount, capped.counts[Rank.THREE])
        assertFalse(capped.isEdited(Rank.THREE))

        var big = HandDraft()
        repeat(10) { big = big.adjust(Rank.BIG_JOKER, +1) }
        assertEquals(4, big.counts[Rank.BIG_JOKER])
    }

    @Test
    fun refreshDoesNotOverwriteEditedRanks() {
        val edited = HandDraft().refresh(counts(Rank.ACE to 2, Rank.KING to 1), 3)
            .adjust(Rank.ACE, +1)
        val refreshed = edited.refresh(counts(Rank.ACE to 0, Rank.KING to 2, Rank.QUEEN to 1), 5)
        assertEquals("改过的点数保持用户的值", 3, refreshed.counts[Rank.ACE])
        assertEquals("没改过的点数照常跟识别走", 2, refreshed.counts[Rank.KING])
        assertEquals(1, refreshed.counts[Rank.QUEEN])
        assertEquals(setOf(Rank.ACE), refreshed.edited)
        assertEquals(5, refreshed.positionCount)
    }

    @Test
    fun refreshCapsRecognizedCountAtInventory() {
        val draft = HandDraft().refresh(counts(Rank.BIG_JOKER to 9), 9)
        assertEquals(4, draft.counts[Rank.BIG_JOKER])
    }

    @Test
    fun cannotConfirmWhenTotalDiffersFromPositionCount() {
        val draft = HandDraft().refresh(counts(Rank.ACE to 2), 3)
        assertFalse(draft.canConfirm)
        assertTrue(draft.mismatch)
        assertEquals("点数合计 2 张，数出来 3 张，必须改到一致才能确认", draft.blockReason)
        assertNull(draft.confirmData())
    }

    @Test
    fun cannotConfirmWhenPositionCountIsZeroOrUnknown() {
        val zero = HandDraft().refresh(RankCounts.zero(), 0)
        assertFalse(zero.canConfirm)
        assertNotNull(zero.blockReason)

        val unknown = HandDraft().refresh(counts(Rank.ACE to 2), null)
        assertFalse(unknown.canConfirm)
        assertFalse("还没数出张数不算对不上", unknown.mismatch)
        assertNull(unknown.confirmData())
    }

    @Test
    fun canConfirmWhenTotalMatchesPositionCount() {
        val draft = HandDraft().refresh(counts(Rank.ACE to 2), 3).adjust(Rank.KING, +1)
        assertTrue(draft.canConfirm)
        assertFalse(draft.mismatch)
        assertNull(draft.blockReason)
        val data = requireNotNull(draft.confirmData())
        assertEquals(counts(Rank.ACE to 2, Rank.KING to 1), data.cards)
        assertEquals(3, data.handCount)
        assertEquals(data.cards.total(), data.handCount)
    }

    @Test
    fun holderClearDropsCountsAndEditedMarks() {
        val holder = HandDraftHolder()
        holder.refresh(counts(Rank.ACE to 2), 3)
        holder.adjust(Rank.KING, +1)
        assertTrue(holder.snapshot().isEdited(Rank.KING))

        holder.clear()
        val cleared = holder.snapshot()
        assertEquals(RankCounts.zero(), cleared.counts)
        assertNull(cleared.positionCount)
        assertTrue(cleared.edited.isEmpty())
    }

    @Test
    fun holderIsSafeUnderConcurrentRefreshAndAdjust() {
        val holder = HandDraftHolder()
        val refresher = Thread {
            repeat(2_000) { holder.refresh(counts(Rank.ACE to 2), 2) }
        }
        val clicker = Thread {
            repeat(2_000) { holder.adjust(Rank.KING, if (it % 2 == 0) +1 else -1) }
        }
        refresher.start(); clicker.start()
        refresher.join(); clicker.join()
        val draft = holder.snapshot()
        assertEquals(2, draft.counts[Rank.ACE])
        assertEquals(0, draft.counts[Rank.KING])
        assertTrue(draft.isEdited(Rank.KING))
    }
}
