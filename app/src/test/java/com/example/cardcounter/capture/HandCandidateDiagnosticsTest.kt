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

    @Test
    fun doesNotStabilizeAnEmptyRankCandidate() {
        val hand = RankCounts.of(mapOf(Rank.ACE to 2))
        assertEquals(1, stableRankCandidateCount(null, 0, hand))
        assertEquals(2, stableRankCandidateCount(hand, 1, hand))
        assertEquals(0, stableRankCandidateCount(hand, 2, RankCounts.zero()))
    }

    @Test
    fun enforcesBaselineConsistencyWithPositionCount() {
        val hand33 = RankCounts.of(mapOf(Rank.TEN to 16, Rank.NINE to 16, Rank.THREE to 1))
        val hand31 = RankCounts.of(mapOf(Rank.TEN to 16, Rank.NINE to 15))
        val hand28 = RankCounts.of(mapOf(Rank.TEN to 16, Rank.NINE to 12))

        // 牌位检测张数与识别张数一致：33/33 接受。
        assertEquals(true, isValidBaseline(33, hand33))
        // 差值超过 ±2：33/28 拒绝，识别漏检不得锁定 H0。
        assertEquals(false, isValidBaseline(33, hand28))
        // 差值在 ±2 内：33/31 接受。
        assertEquals(true, isValidBaseline(33, hand31))
        // 无牌位检测结果：一律拒绝。
        assertEquals(false, isValidBaseline(null, hand31))
        // 空手牌：拒绝。
        assertEquals(false, isValidBaseline(33, RankCounts.zero()))
    }

    @Test
    fun tableVisibleByGreenRatioOrRecentActivity() {
        // 绿点占比恰好 60%：无活动也可见。
        assertEquals(true, isTableVisibleBySamples(6, 10, hasRecentActivity = false))
        // 绿点占比 50%：无活动不可见。
        assertEquals(false, isTableVisibleBySamples(5, 10, hasRecentActivity = false))
        // 无绿点且无活动：不可见。
        assertEquals(false, isTableVisibleBySamples(0, 8, hasRecentActivity = false))
        // 绿点不足但最近有出牌活动：豁免可见。
        assertEquals(true, isTableVisibleBySamples(1, 8, hasRecentActivity = true))
    }

    @Test
    fun keepsValidBaselineThroughAnyPrePlayTransition() {
        assertEquals(
            false,
            shouldInvalidatePrePlayHand(
                firstPlayArmed = false,
                hasStableHand = true,
                handChanged = true
            )
        )
        assertEquals(
            true,
            shouldInvalidatePrePlayHand(
                firstPlayArmed = false,
                hasStableHand = false,
                handChanged = true
            )
        )
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
    fun locksAfterTwoStableHandSamples() {
        val gate = FirstPlayBoundaryGate()
        val frame = OpeningSignals(controlsVisible = false, centerCardsVisible = false)

        assertEquals(FirstPlayBoundary.NONE, gate.update(frame, hasStableHand = false))
        assertEquals(FirstPlayBoundary.NONE, gate.update(frame, hasStableHand = true))
        assertEquals(FirstPlayBoundary.LOCK, gate.update(frame, hasStableHand = true))
        assertEquals(FirstPlayBoundary.NONE, gate.update(frame, hasStableHand = true))
    }

    @Test
    fun keepsWaitingWhenFirstPlayArrivesWithoutBaseline() {
        val gate = FirstPlayBoundaryGate()
        val frame = OpeningSignals(controlsVisible = false, centerCardsVisible = false)

        assertEquals(FirstPlayBoundary.NONE, gate.update(frame, hasStableHand = false))
        assertEquals(FirstPlayBoundary.NONE, gate.update(frame, hasStableHand = false))
    }

    @Test
    fun newSessionRearmsFirstPlayBoundary() {
        val gate = FirstPlayBoundaryGate()
        val frame = OpeningSignals(controlsVisible = false, centerCardsVisible = false)
        gate.update(frame, hasStableHand = true)
        assertEquals(FirstPlayBoundary.LOCK, gate.update(frame, hasStableHand = true))
        gate.reset()
        gate.update(frame, hasStableHand = true)
        assertEquals(FirstPlayBoundary.LOCK, gate.update(frame, hasStableHand = true))
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
        assertEquals(false, canReachIoUThreshold(sceneInk = 2, templateInk = 10, threshold = 0.55))
        assertEquals(true, canReachIoUThreshold(sceneInk = 8, templateInk = 10, threshold = 0.55))
    }

    @Test
    fun keepsAdjacentJokersAtTheirNarrowerPitch() {
        val matches = listOf(
            HandRankMatcher.Match(100, Rank.SMALL_JOKER, 1.0),
            HandRankMatcher.Match(125, Rank.SMALL_JOKER, 0.95)
        )

        assertEquals(2, selectDistinctMatches(matches, maxCount = 8, minGap = 20).size)
    }

}
