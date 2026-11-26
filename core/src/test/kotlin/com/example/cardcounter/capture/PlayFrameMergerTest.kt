package com.example.cardcounter.capture

import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.vision.YoloDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayFrameMergerTest {
    private fun det(rank: Rank, x: Float, y: Float = 160f, conf: Float = 0.85f) =
        YoloDetection(rank, rank.name, conf, x, y, 24f, 32f)

    private fun counts(vararg pairs: Pair<Rank, Int>) = RankCounts.of(pairs.toMap())

    @Test
    fun divergentFramesWithMissingLowConfFourAndFullFourNeverConfirmThreeFours() {
        // 事故：一帧漏掉低把握的 4 得 3 张 4（把握度都 >= 0.75），另一帧认全 4 张 4（最低把握度 0.28）。
        // 绝对不能因为 canFastConfirmPlay 单票偏向漏牌帧而记成 3 张 4，必须合并成 4 张 4。
        val frameMissing = listOf(
            det(Rank.FOUR, 60f, conf = 0.75f),
            det(Rank.FOUR, 90f, conf = 0.82f),
            det(Rank.FOUR, 120f, conf = 0.79f)
        )
        val frameFull = listOf(
            det(Rank.FOUR, 61f, conf = 0.76f),
            det(Rank.FOUR, 90f, conf = 0.81f),
            det(Rank.FOUR, 121f, conf = 0.78f),
            det(Rank.FOUR, 150f, conf = 0.28f)
        )

        val outcome1 = requireNotNull(PlayFrameVote.resolve(listOf(frameMissing, frameFull)))
        assertEquals(counts(Rank.FOUR to 4), outcome1.cards)
        assertTrue(outcome1.mergedFromDisagreement)
        assertEquals(2, outcome1.framesUsed)

        // 帧顺序颠倒结果一致。
        val outcome2 = requireNotNull(PlayFrameVote.resolve(listOf(frameFull, frameMissing)))
        assertEquals(counts(Rank.FOUR to 4), outcome2.cards)
        assertTrue(outcome2.mergedFromDisagreement)
    }

    @Test
    fun singleFrameAllowsFastConfirmOnlyWhenAtLeastThreeCardsAndHighConfidence() {
        val highThree = listOf(
            det(Rank.FIVE, 60f, conf = 0.70f),
            det(Rank.FIVE, 90f, conf = 0.65f),
            det(Rank.FIVE, 120f, conf = 0.60f)
        )
        val ok = requireNotNull(PlayFrameVote.resolve(listOf(highThree)))
        assertEquals(counts(Rank.FIVE to 3), ok.cards)
        assertFalse(ok.mergedFromDisagreement)

        // 单帧有低把握牌：禁止单帧快速确认。
        val lowThree = listOf(
            det(Rank.FIVE, 60f, conf = 0.80f),
            det(Rank.FIVE, 90f, conf = 0.75f),
            det(Rank.FIVE, 120f, conf = 0.59f)
        )
        assertNull(PlayFrameVote.resolve(listOf(lowThree)))

        // 单帧不足 3 张：禁止单帧快速确认。
        val twoCards = listOf(det(Rank.ACE, 60f, conf = 0.95f), det(Rank.ACE, 90f, conf = 0.95f))
        assertNull(PlayFrameVote.resolve(listOf(twoCards)))
    }

    @Test
    fun sameSlotDifferentRanksAcrossFramesKeepHighestConfidence() {
        val f1 = listOf(det(Rank.NINE, 100f, conf = 0.45f))
        val f2 = listOf(det(Rank.ACE, 101f, conf = 0.88f))
        val outcome = requireNotNull(PlayFrameVote.resolve(listOf(f1, f2)))
        assertEquals(counts(Rank.ACE to 1), outcome.cards)
    }

    @Test
    fun overlappingBoxesWithinSingleFrameAreKeptAsDistinctSlots() {
        val f1 = listOf(det(Rank.TWO, 160f, conf = 0.9f), det(Rank.TWO, 160f, conf = 0.9f))
        val f2 = listOf(det(Rank.TWO, 160f, conf = 0.9f), det(Rank.TWO, 160f, conf = 0.9f))
        val outcome = requireNotNull(PlayFrameVote.resolve(listOf(f1, f2)))
        assertEquals(counts(Rank.TWO to 2), outcome.cards)
        assertFalse(outcome.mergedFromDisagreement)
    }
}
