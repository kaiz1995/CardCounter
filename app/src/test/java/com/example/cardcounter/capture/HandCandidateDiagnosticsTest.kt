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
    fun acceptsStableBaselineAtItsActualHandCount() {
        assertEquals(
            true,
            isValidBaseline(31, RankCounts.of(mapOf(Rank.TEN to 16, Rank.NINE to 15)))
        )
        assertEquals(
            true,
            isValidBaseline(30, RankCounts.of(mapOf(Rank.TEN to 16, Rank.NINE to 13)))
        )
        assertEquals(
            true,
            isValidBaseline(35, RankCounts.of(mapOf(Rank.TEN to 16, Rank.NINE to 16, Rank.THREE to 3)))
        )
        // 任意张数门槛：位置检测计数不再参与，33 张或非 33 张手牌都只要求非空且在库存内。
        assertEquals(
            true,
            isValidBaseline(33, RankCounts.of(mapOf(Rank.TEN to 16, Rank.NINE to 16, Rank.THREE to 3)))
        )
        assertEquals(
            true,
            isValidBaseline(22, RankCounts.of(mapOf(Rank.ACE to 16, Rank.KING to 6)))
        )
        assertEquals(
            false,
            isValidBaseline(22, RankCounts.zero())
        )
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
