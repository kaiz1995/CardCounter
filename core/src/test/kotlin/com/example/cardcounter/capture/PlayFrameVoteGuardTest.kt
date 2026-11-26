package com.example.cardcounter.capture

import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.vision.YoloDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 出牌区多帧合并的守卫测试：每个用例对应一类会记错账的情形，撤掉对应改动就会失败。 */
class PlayFrameVoteGuardTest {
    private fun det(
        rank: Rank,
        x: Float,
        y: Float = 160f,
        conf: Float = 0.85f,
        w: Float = 24f,
        h: Float = 32f
    ) = YoloDetection(rank, rank.name, conf, x, y, w, h)

    private fun counts(vararg pairs: Pair<Rank, Int>) = RankCounts.of(pairs.toMap())

    private val fourMissing = listOf(
        det(Rank.FOUR, 60f, conf = 0.75f),
        det(Rank.FOUR, 90f, conf = 0.82f),
        det(Rank.FOUR, 120f, conf = 0.79f)
    )
    private val fourFull = listOf(
        det(Rank.FOUR, 61f, conf = 0.76f),
        det(Rank.FOUR, 90f, conf = 0.81f),
        det(Rank.FOUR, 121f, conf = 0.78f),
        det(Rank.FOUR, 150f, conf = 0.28f)
    )

    // ---- a) 牌位匹配按检测框自身尺寸 ----

    @Test
    fun largeBoxesFromTopZoneToleratePixelJitterThatFixedEightPixelsWouldSplit() {
        // 顶部区放大约 3.9 倍：框宽约 40、高约 60，帧间抖动 9/14 像素很常见，旧的固定 8/12 会把同一张牌拆成两张。
        fun frame(dx: Float, dy: Float, withFourth: Boolean) = buildList {
            add(det(Rank.SEVEN, 100f + dx, 160f + dy, w = 40f, h = 60f))
            add(det(Rank.SEVEN, 160f + dx, 160f + dy, w = 40f, h = 60f))
            add(det(Rank.SEVEN, 220f + dx, 160f + dy, w = 40f, h = 60f))
            if (withFourth) add(det(Rank.SEVEN, 280f + dx, 160f + dy, w = 40f, h = 60f))
        }
        val outcome = requireNotNull(PlayFrameVote.resolve(listOf(frame(0f, 0f, false), frame(9f, 14f, true))))
        assertEquals(counts(Rank.SEVEN to 4), outcome.cards)
    }

    @Test
    fun smallBoxesAreNotMergedWhenTheyAreDifferentCards() {
        // 右侧区放大约 2.3 倍：框宽约 18，相邻两张牌中心相距 16，容差随框缩小，不能把两张并成一张。
        val f1 = listOf(det(Rank.FIVE, 100f, w = 18f, h = 26f))
        val f2 = listOf(det(Rank.FIVE, 100f, w = 18f, h = 26f), det(Rank.FIVE, 116f, w = 18f, h = 26f))
        val outcome = requireNotNull(PlayFrameVote.resolve(listOf(f1, f2)))
        assertEquals(counts(Rank.FIVE to 2), outcome.cards)
    }

    @Test
    fun slotMatchingScalesWithBoxSize() {
        val small = det(Rank.FIVE, 100f, w = 18f, h = 26f)
        assertTrue(PlayFrameVote.sameSlot(small, small.copy(centerX = 108f)))
        assertFalse(PlayFrameVote.sameSlot(small, small.copy(centerX = 110f)))
        val large = det(Rank.FIVE, 100f, w = 40f, h = 60f)
        assertTrue(PlayFrameVote.sameSlot(large, large.copy(centerX = 119f, centerY = 189f)))
        assertFalse(PlayFrameVote.sameSlot(large, large.copy(centerX = 121f)))
    }

    // ---- b) 停稳帧 >= 3 时过滤单帧假阳性 ----

    @Test
    fun singleFrameBoxWithAnotherMainRankIsDroppedWhenThreeFramesAreStable() {
        val noisy = fourMissing + det(Rank.NINE, 150f, conf = 0.40f)
        val outcome = requireNotNull(PlayFrameVote.resolve(listOf(fourMissing, noisy, fourMissing)))
        assertEquals(counts(Rank.FOUR to 3), outcome.cards)
    }

    @Test
    fun singleFrameBoxFarAwayIsDroppedEvenWithTheSameRank() {
        val noisy = fourMissing + det(Rank.FOUR, 400f, conf = 0.40f)
        val outcome = requireNotNull(PlayFrameVote.resolve(listOf(fourMissing, noisy, fourMissing)))
        assertEquals(counts(Rank.FOUR to 3), outcome.cards)
    }

    @Test
    fun singleFrameWildCardNextToTheHandIsKept() {
        val withJoker = fourMissing + det(Rank.BIG_JOKER, 150f, conf = 0.40f)
        val outcome = requireNotNull(PlayFrameVote.resolve(listOf(fourMissing, withJoker, fourMissing)))
        assertEquals(counts(Rank.FOUR to 3, Rank.BIG_JOKER to 1), outcome.cards)
    }

    @Test
    fun twoFramesKeepTheUnion() {
        // 只有 2 帧时没法区分漏牌和假阳性，保持并集（混点数由规则校验在后面拦下）。
        val noisy = fourMissing + det(Rank.NINE, 150f, conf = 0.40f)
        val outcome = requireNotNull(PlayFrameVote.resolve(listOf(fourMissing, noisy)))
        assertEquals(counts(Rank.FOUR to 3, Rank.NINE to 1), outcome.cards)
        assertTrue(outcome.mergedFromDisagreement)
    }

    // ---- c) 合并结果不合规时退回出现 >= 2 帧的合规帧 ----

    @Test
    fun mixedMergeFallsBackToTheFrameResultSeenTwiceThatPassesTheRules() {
        val fours = fourMissing
        val fiveAtEnd = listOf(
            det(Rank.FOUR, 60f, conf = 0.75f),
            det(Rank.FOUR, 90f, conf = 0.82f),
            det(Rank.FIVE, 120f, conf = 0.90f)
        )
        val outcome = requireNotNull(PlayFrameVote.resolve(listOf(fours, fours, fiveAtEnd, fiveAtEnd)))
        assertEquals(counts(Rank.FOUR to 3), outcome.cards)
        assertTrue(outcome.fellBackToFrame)
        assertFalse(outcome.mergedFromDisagreement)
    }

    @Test
    fun withoutAnyValidRepeatedFrameTheMergedResultIsReturnedForTheRulesToBlock() {
        val noisy = fourMissing + det(Rank.NINE, 150f, conf = 0.40f)
        val outcome = requireNotNull(PlayFrameVote.resolve(listOf(fourMissing, noisy)))
        assertFalse(outcome.fellBackToFrame)
        assertTrue(outcome.mergedFromDisagreement)
    }

    // ---- d) 今天的事故：一帧漏掉低把握的 4，另一帧认全，仍要记全 ----

    @Test
    fun lowConfidenceFourStillCountedWithTwoThreeAndFourFrames() {
        val two = requireNotNull(PlayFrameVote.resolve(listOf(fourMissing, fourFull)))
        assertEquals(counts(Rank.FOUR to 4), two.cards)

        val three = requireNotNull(PlayFrameVote.resolve(listOf(fourMissing, fourFull, fourMissing)))
        assertEquals(counts(Rank.FOUR to 4), three.cards)

        val four = requireNotNull(PlayFrameVote.resolve(listOf(fourFull, fourMissing, fourMissing, fourMissing)))
        assertEquals(counts(Rank.FOUR to 4), four.cards)
    }
}
