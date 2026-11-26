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

    /** 识别值超库存时原样保留，由 blockReason 拦下，不再悄悄截断成库存。 */
    @Test
    fun refreshKeepsOverInventoryRecognitionAndBlocksConfirm() {
        val draft = HandDraft().refresh(counts(Rank.BIG_JOKER to 9), 9)
        assertEquals(9, draft.counts[Rank.BIG_JOKER])
        assertFalse(draft.canConfirm)
        assertEquals("有点数超过库存，必须改回库存以内才能确认", draft.blockReason)
        assertNull(draft.confirmData())
    }

    /** 超库存的点数只能往下减，减到库存以内后可以确认。 */
    @Test
    fun overInventoryRankCanOnlyBeReducedBackIntoInventory() {
        val over = HandDraft().refresh(counts(Rank.BIG_JOKER to 5), 4)
        val same = over.adjust(Rank.BIG_JOKER, +1)
        assertEquals("超库存时再加一张没有变化", over, same)
        val fixed = over.adjust(Rank.BIG_JOKER, -1)
        assertEquals(4, fixed.counts[Rank.BIG_JOKER])
        assertTrue(fixed.canConfirm)
    }

    /** 回归：误触 +1 再 -1，点数回到识别值，不能留下 edited 永久绕过张数校验。 */
    @Test
    fun adjustBackToRecognizedValueClearsEditedMark() {
        val draft = HandDraft().refresh(counts(Rank.ACE to 2), 3)
        val back = draft.adjust(Rank.ACE, +1).adjust(Rank.ACE, -1)
        assertTrue(back.edited.isEmpty())
        assertEquals(draft.counts, back.counts)
        assertFalse("合计 2、数出来 3，仍要拦", back.canConfirm)
    }

    /** 识别后来追上了用户改的值，该点数不再算改过，之后跟着识别走。 */
    @Test
    fun refreshThatCatchesUpWithEditedRankClearsEditedMark() {
        val edited = HandDraft().refresh(counts(Rank.ACE to 2), 3).adjust(Rank.ACE, +1)
        val caught = edited.refresh(counts(Rank.ACE to 3), 3)
        assertTrue(caught.edited.isEmpty())
        assertEquals(3, caught.counts[Rank.ACE])
        val next = caught.refresh(counts(Rank.ACE to 2), 2)
        assertEquals(2, next.counts[Rank.ACE])
    }

    /** 用户改过点数后，合计和数出来的张数差 2 张及以上，不能一键放行；差 1 张只提示。 */
    @Test
    fun editedTotalFarFromPositionCountIsBlocked() {
        val far = HandDraft().refresh(counts(Rank.ACE to 2), 10).adjust(Rank.KING, +1)
        assertEquals(3, far.total)
        assertFalse(far.canConfirm)
        assertEquals("点数合计 3 张，数出来 10 张，差得太多；请再核对点数，对上后就能确认", far.blockReason)
        assertNull(far.warning)
        assertNull(far.confirmData())

        val gapTwo = HandDraft().refresh(counts(Rank.ACE to 2), 5).adjust(Rank.KING, +1)
        assertFalse("差 2 张要拦", gapTwo.canConfirm)

        val gapOne = HandDraft().refresh(counts(Rank.ACE to 2), 4).adjust(Rank.KING, +1)
        assertTrue("差 1 张只提示", gapOne.canConfirm)
        assertNotNull(gapOne.warning)
    }

    /** 版本号：内容变了才涨；内容没变的重复刷新不涨；新局清空后继续往上走。 */
    @Test
    fun versionOnlyChangesWhenContentChanges() {
        val first = HandDraft().refresh(counts(Rank.ACE to 2), 2)
        val repeated = first.refresh(counts(Rank.ACE to 2), 2)
        assertEquals(first.version, repeated.version)

        val changed = repeated.refresh(counts(Rank.ACE to 3), 3)
        assertTrue(changed.version > repeated.version)

        val adjusted = changed.adjust(Rank.KING, +1)
        assertTrue(adjusted.version > changed.version)
        val noChange = HandDraft().adjust(Rank.KING, -1)
        assertEquals("到下限没有实际变化的加减不涨版本", 0L, noChange.version)

        val holder = HandDraftHolder()
        holder.refresh(counts(Rank.ACE to 2), 2)
        val before = holder.snapshot().version
        holder.clear()
        assertTrue("清空后版本号继续往上", holder.snapshot().version > before)
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

    /** 数出 0 张等于没数出来：不标红、不提示对不上，确认时张数记 null，不把 0 写进账里。 */
    @Test
    fun zeroPositionCountIsTreatedAsUnknown() {
        val untouched = HandDraft().refresh(counts(Rank.ACE to 2), 0)
        assertFalse("数出 0 张不算对不上", untouched.mismatch)
        assertEquals("还没数出手牌张数，暂时不能确认", untouched.blockReason)

        val edited = untouched.adjust(Rank.ACE, +1)
        assertFalse(edited.mismatch)
        assertNull(edited.warning)
        assertTrue(edited.canConfirm)
        val data = requireNotNull(edited.confirmData())
        assertEquals(3, data.cards.total())
        assertNull(data.handCount)
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

    /** 所见即所确认：点击时的版本和现在一致才确认；后台刷新或又改过一次，版本变了就不确认。 */
    @Test
    fun gateConfirmsOnlyTheVersionTheUserClicked() {
        val seen = HandDraft().refresh(counts(Rank.ACE to 2), 2)
        val ready = seen.gate(seen.version)
        assertTrue(ready is HandConfirmGate.Ready)
        assertEquals(counts(Rank.ACE to 2), (ready as HandConfirmGate.Ready).data.cards)

        val refreshed = seen.refresh(counts(Rank.ACE to 3), 3)
        assertEquals(HandConfirmGate.Stale, refreshed.gate(seen.version))

        val adjusted = seen.adjust(Rank.KING, +1)
        assertEquals(HandConfirmGate.Stale, adjusted.gate(seen.version))
        assertTrue(adjusted.gate(adjusted.version) is HandConfirmGate.Ready)
    }

    @Test
    fun gateReportsBlockedWhenTheClickedVersionCannotBeConfirmed() {
        val mismatch = HandDraft().refresh(counts(Rank.ACE to 2), 3)
        assertEquals(HandConfirmGate.Blocked, mismatch.gate(mismatch.version))
    }

    /** 内容没变的重复刷新不涨版本：用户看着没变的画面点确认，不会被后台的重复刷新误判成过期。 */
    @Test
    fun repeatedIdenticalRefreshKeepsTheClickedVersionValid() {
        val seen = HandDraft().refresh(counts(Rank.ACE to 2), 2)
        val again = seen.refresh(counts(Rank.ACE to 2), 2)
        assertTrue(again.gate(seen.version) is HandConfirmGate.Ready)
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
        // 加一减一后回到识别值（0），不再算改过，不会永久绕过张数校验。
        assertFalse(draft.isEdited(Rank.KING))
        assertTrue(draft.edited.isEmpty())
    }
}
