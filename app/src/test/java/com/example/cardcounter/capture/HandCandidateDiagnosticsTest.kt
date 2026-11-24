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
    fun showsTheRecognitionWhoseTotalMatchesTheEdgeCount() {
        val model = RankCounts.of(mapOf(Rank.TEN to 16, Rank.NINE to 15))
        val template = RankCounts.of(mapOf(Rank.TEN to 16, Rank.NINE to 14))
        assertEquals(model, chooseHandCards(31, model, template))
        assertEquals(template, chooseHandCards(30, model, template))
    }

    @Test
    fun showsTheModelWhenNeitherMatchesAndTheTemplateWhenThereIsNoModel() {
        val model = RankCounts.of(mapOf(Rank.TEN to 16, Rank.NINE to 15))
        val template = RankCounts.of(mapOf(Rank.TEN to 16, Rank.NINE to 14))
        assertEquals(model, chooseHandCards(40, model, template))
        assertEquals(model, chooseHandCards(null, model, template))
        assertEquals(template, chooseHandCards(40, null, template))
        assertEquals(template, chooseHandCards(40, RankCounts.zero(), template))
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
