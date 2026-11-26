package com.example.cardcounter.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts

class HandCandidateDiagnosticsTest {
    @Test
    fun reportsOnlyComparableSignatures() {
        assertNull(handChangePercent(null, intArrayOf(0)))
        assertNull(handChangePercent(intArrayOf(0), intArrayOf(0, 1)))
        assertEquals(0.0, handChangePercent(intArrayOf(10, 20), intArrayOf(10, 20))!!, 0.001)
        assertEquals(100.0, handChangePercent(intArrayOf(0), intArrayOf(255))!!, 0.001)
    }

    /** 回归：手牌实际 34 张、边缘只数出 33 张时，不能把对的模型结果换成恰好 33 张的错模板结果。 */
    @Test
    fun showsTheModelWheneverItHasAResult() {
        val model = RankCounts.of(mapOf(Rank.TEN to 16, Rank.NINE to 15))
        val template = RankCounts.of(mapOf(Rank.TEN to 16, Rank.NINE to 14))
        assertEquals(model, chooseHandCards(modelLoaded = true, model = model) { template })
    }

    /** 模型已加载但这一帧没结果：本帧不刷新草稿，不拿模板顶替，也不白跑模板匹配。 */
    @Test
    fun loadedModelWithoutAResultSkipsTheFrameAndNeverRunsTheTemplate() {
        var templateRuns = 0
        val template = RankCounts.of(mapOf(Rank.TEN to 16, Rank.NINE to 14))
        assertNull(chooseHandCards(modelLoaded = true, model = null) { templateRuns++; template })
        assertNull(chooseHandCards(modelLoaded = true, model = RankCounts.zero()) { templateRuns++; template })
        assertEquals(0, templateRuns)
    }

    @Test
    fun usesTheTemplateOnlyWhenTheModelIsNotLoaded() {
        val template = RankCounts.of(mapOf(Rank.TEN to 16, Rank.NINE to 14))
        assertEquals(template, chooseHandCards(modelLoaded = false, model = null) { template })
    }

    @Test
    fun allowsOnlyOneRankProbeAtATime() {
        val gate = SingleFlightGate()

        assertEquals(true, gate.tryAcquire())
        assertEquals(false, gate.tryAcquire())
        gate.release()
        assertEquals(true, gate.tryAcquire())
    }

    @Test
    fun mergesRaisedAndStandardRowMatchesWithoutDuplicates() {
        val matches = listOf(
            HandRankMatcher.Match(100, Rank.FIVE, 1.0),
            HandRankMatcher.Match(102, Rank.TEN, 0.7),
            HandRankMatcher.Match(145, Rank.FOUR, 0.8)
        )

        assertEquals(
            listOf(Rank.FIVE, Rank.FOUR),
            selectDistinctMatches(matches, maxCount = 40).map { it.rank }
        )
    }

    @Test
    fun skipsTemplateScoresWhoseInkDensityCannotReachThreshold() {
        assertEquals(false, canReachIoUThreshold(sceneInk = 2, templatePixels = 10, threshold = 0.55))
        assertEquals(true, canReachIoUThreshold(sceneInk = 8, templatePixels = 10, threshold = 0.55))
    }

    @Test
    fun keepsAdjacentJokersAtTheirNarrowerPitch() {
        val matches = listOf(
            HandRankMatcher.Match(100, Rank.SMALL_JOKER, 1.0),
            HandRankMatcher.Match(125, Rank.SMALL_JOKER, 0.95)
        )

        assertEquals(2, selectDistinctMatches(matches, maxCount = 8, minGap = 20).size)
    }

    @Test
    fun keepsTwoSameRankMatchesFartherApartThanTheDefaultGap() {
        // 手牌普通点数用默认间距 28：同点数的两张牌间距大于它都保留，间距不大于它的才算同一张。
        val matches = listOf(
            HandRankMatcher.Match(100, Rank.SEVEN, 1.0),
            HandRankMatcher.Match(129, Rank.SEVEN, 0.9),
            HandRankMatcher.Match(150, Rank.SEVEN, 0.8)
        )

        assertEquals(listOf(100, 129), selectDistinctMatches(matches, maxCount = 40).map { it.x })
    }

}
