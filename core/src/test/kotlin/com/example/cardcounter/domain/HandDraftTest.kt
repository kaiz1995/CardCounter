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
    fun cannotConfirmWhenNothingEditedAndTotalDiffersFromPositionCount() {
        val draft = HandDraft().refresh(counts(Rank.ACE to 2), 3)
        assertFalse(draft.canConfirm)
        assertTrue(draft.mismatch)
        assertEquals(
            "点数合计 2 张，数出来 3 张，对不上；核对后可以点点数加减改正，改完就能确认",
            draft.blockReason
        )
        assertNull(draft.warning)
        assertNull(draft.confirmData())
    }

    /** 回归：实际 34 张，边缘只数出 33 张，用户给 7 加一张后合计 34，必须能直接确认。 */
    @Test
    fun canConfirmWithWarningWhenEditedAndTotalDiffersFromPositionCount() {
        val draft = HandDraft().refresh(counts(Rank.ACE to 2), 3).adjust(Rank.KING, +2)
        assertEquals(4, draft.total)
        assertTrue(draft.mismatch)
        assertNull(draft.blockReason)
        assertTrue(draft.canConfirm)
        assertEquals("数出来 3 张，和点数合计 4 张不一样，核对没错就直接确认", draft.warning)
        val data = requireNotNull(draft.confirmData())
        assertEquals(4, data.cards.total())
        assertEquals(3, data.handCount)
    }

    @Test
    fun canConfirmWhenEditedAndPositionCountIsUnknown() {
        val draft = HandDraft().refresh(counts(Rank.ACE to 2), null).adjust(Rank.ACE, +1)
        assertTrue(draft.canConfirm)
        assertNull(draft.warning)
        val data = requireNotNull(draft.confirmData())
        assertEquals(3, data.cards.total())
        assertNull(data.handCount)
    }

    @Test
    fun cannotConfirmWhenNothingEditedAndPositionCountIsZeroOrUnknown() {
        val zero = HandDraft().refresh(RankCounts.zero(), 0)
        assertFalse(zero.canConfirm)
        assertNotNull(zero.blockReason)

        val unknown = HandDraft().refresh(counts(Rank.ACE to 2), null)
        assertFalse(unknown.canConfirm)
        assertFalse("还没数出张数不算对不上", unknown.mismatch)
        assertEquals("还没数出手牌张数，暂时不能确认", unknown.blockReason)
        assertNull(unknown.confirmData())
    }

    @Test
    fun cannotConfirmWhenTotalIsZeroEvenIfEdited() {
        val draft = HandDraft().refresh(counts(Rank.ACE to 1), null)
            .adjust(Rank.ACE, -1)
        assertTrue(draft.edited.isNotEmpty())
        assertEquals(0, draft.total)
        assertFalse(draft.canConfirm)
        assertNotNull(draft.blockReason)
    }

    @Test
    fun cannotConfirmWhenAnyRankExceedsInventory() {
        val over = HandDraft(counts(Rank.BIG_JOKER to 5), positionCount = 5, edited = setOf(Rank.BIG_JOKER))
        assertFalse(over.canConfirm)
        assertEquals("有点数超过库存，必须改回库存以内才能确认", over.blockReason)
        assertNull(over.confirmData())
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
