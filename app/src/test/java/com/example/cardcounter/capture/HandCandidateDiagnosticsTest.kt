package com.example.cardcounter.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.vision.YoloDetection

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
        assertEquals(
            false,
            isValidBaseline(33, RankCounts.of(mapOf(Rank.TEN to 16, Rank.NINE to 16, Rank.THREE to 3)))
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

        assertEquals(FirstPlayBoundary.NONE, gate.update(hasStableHand = false))
        assertEquals(FirstPlayBoundary.NONE, gate.update(hasStableHand = true))
        assertEquals(FirstPlayBoundary.LOCK, gate.update(hasStableHand = true))
        assertEquals(FirstPlayBoundary.NONE, gate.update(hasStableHand = true))
    }

    @Test
    fun keepsWaitingWhenFirstPlayArrivesWithoutBaseline() {
        val gate = FirstPlayBoundaryGate()

        assertEquals(FirstPlayBoundary.NONE, gate.update(hasStableHand = false))
        assertEquals(FirstPlayBoundary.NONE, gate.update(hasStableHand = false))
    }

    @Test
    fun newSessionRearmsFirstPlayBoundary() {
        val gate = FirstPlayBoundaryGate()
        gate.update(hasStableHand = true)
        assertEquals(FirstPlayBoundary.LOCK, gate.update(hasStableHand = true))
        gate.reset()
        gate.update(hasStableHand = true)
        assertEquals(FirstPlayBoundary.LOCK, gate.update(hasStableHand = true))
    }

    @Test
    fun resetsReadySamplesWhenAStableHandFrameIsLost() {
        val gate = FirstPlayBoundaryGate()

        gate.update(hasStableHand = true)
        gate.update(hasStableHand = false)
        assertEquals(FirstPlayBoundary.NONE, gate.update(hasStableHand = true))
        assertEquals(FirstPlayBoundary.LOCK, gate.update(hasStableHand = true))
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
        assertEquals(false, canReachInkRatio(sceneInk = 2, templateInk = 10, threshold = 0.55))
        assertEquals(true, canReachInkRatio(sceneInk = 8, templateInk = 10, threshold = 0.55))
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
    fun averagesBatchConfidenceSoOneWeakCardCannotSinkIt() {
        val detections = List(11) { detection(Rank.FIVE, 0.95f) } + detection(Rank.TWO, 0.26f)

        val confidence = aggregateConfidence(detections)

        assertEquals(0.892f, confidence, 0.001f)
        assertEquals(true, canFastConfirmPlay(RankCounts.of(mapOf(Rank.FIVE to 11)), confidence))
    }

    @Test
    fun reportsZeroConfidenceForAnEmptyDetectionBatch() {
        assertEquals(0f, aggregateConfidence(emptyList()), 0.0001f)
        assertEquals(0f, aggregateConfidence(listOf(detection(Rank.ACE, 0f))), 0.0001f)
    }

    @Test
    fun collapsesTheSameCardMatchedOnTwoNearbyRows() {
        // 同一张牌在 y=380 / y=385 两行各成峰，两行的 x 只差 6（<= minGap 28）。
        // 逐行各自去重时，较弱的峰不会被本行淘汰；只有把两行候选合并后再统一去重才能收敛成一张。
        val candidates = listOf(
            HandRankMatcher.Match(500, Rank.NINE, 0.90), // y=380 行的峰值
            HandRankMatcher.Match(494, Rank.NINE, 0.88)  // y=385 行的峰值：同一张牌
        )

        val swept = sweepAcrossRows(candidates, maxCount = 40, minGap = 28)

        assertEquals(1, swept.size)
        assertEquals(500, swept.first().x)
    }

    @Test
    fun keepsEveryDistinctCardWhenNoTwoPeaksAreCloseEnough() {
        // 两张真正分开的牌（间距远超 minGap）必须都保留，不能被误合并。
        val candidates = listOf(
            HandRankMatcher.Match(500, Rank.NINE, 0.90),
            HandRankMatcher.Match(560, Rank.NINE, 0.85)
        )

        val swept = sweepAcrossRows(candidates, maxCount = 40, minGap = 28)

        assertEquals(2, swept.size)
    }

    @Test
    fun capsSweptCandidatesAtMaxCount() {
        val candidates = List(6) { index -> HandRankMatcher.Match(100 + index * 40, Rank.SEVEN, 1.0 - index * 0.1) }

        assertEquals(3, sweepAcrossRows(candidates, maxCount = 3, minGap = 28).size)
    }

    @Test
    fun keepsHandYoloCanvasSquareAtTheConfiguredRoiWidth() {
        val layout = handYoloCanvasLayout(left = 180, top = 350, right = 1_050, bottom = 490, padTop = 540)

        assertEquals(870, layout.canvasSize)
        assertEquals(870, layout.roiWidth)
        assertEquals(140, layout.roiHeight)
        assertEquals(540, layout.padTop)
        assertEquals(true, layout.padTop + layout.roiHeight <= layout.canvasSize)
    }

    @Test
    fun rejectsDegenerateHandYoloRoi() {
        assertThrows(IllegalArgumentException::class.java) {
            handYoloCanvasLayout(left = 180, top = 350, right = 180, bottom = 490, padTop = 540)
        }
        assertThrows(IllegalArgumentException::class.java) {
            handYoloCanvasLayout(left = 180, top = 490, right = 1_050, bottom = 490, padTop = 540)
        }
    }

    private fun detection(rank: Rank, confidence: Float) = YoloDetection(
        rank = rank,
        rawLabel = rank.name,
        confidence = confidence,
        centerX = 0.5f,
        centerY = 0.5f,
        width = 0.1f,
        height = 0.1f
    )
}
