package com.example.cardcounter.capture

import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StablePlayGateTest {
    private val fives = RankCounts.of(mapOf(Rank.FIVE to 4))

    @Test
    fun classifiesCentralPlayAsSelfAndOthersAsOutside() {
        assertEquals(PlaySource.SELF, PlayZone.SELF.source)
        assertEquals(PlaySource.OUTSIDE, PlayZone.LEFT_BOTTOM.source)
    }

    @Test
    fun scalesZonesWithTheGameCanvas() {
        assertTrue(kotlin.math.abs(PlayZone.SELF.left(1_280) * 2 - PlayZone.SELF.left(2_560)) <= 1)
        assertTrue(kotlin.math.abs(PlayZone.LEFT_BOTTOM.rankY(578) * 2 - PlayZone.LEFT_BOTTOM.rankY(1_156)) <= 1)
        assertTrue(PlayZone.SELF.visibilityHeight < PlayZone.TOP.visibilityHeight)
    }

    @Test
    fun publicPlayCropUsesZoneHeightForLetterboxInput() {
        val top = PlayZone.TOP.yoloCrop(1280, 578)
        val left = PlayZone.LEFT_TOP.yoloCrop(1280, 578)
        assertEquals(82, top.width)
        assertEquals(70, top.height)
        assertEquals(152, left.width)
        assertEquals(56, left.height)
        assertEquals(161, PlayZone.SELF.yoloCrop(1280, 578).height)
    }

    @Test
    fun acceptsStablePlayOnceAndAllowsSameCardsAfterClear() {
        val gate = StablePlayGate()
        assertNull(gate.update(PlayZone.SELF, fives))
        assertEquals(StablePlay(PlayZone.SELF, fives), gate.update(PlayZone.SELF, fives))
        assertNull(gate.update(PlayZone.SELF, fives))
        repeat(6) { assertNull(gate.update(PlayZone.SELF, RankCounts.zero())) }
        assertNull(gate.update(PlayZone.SELF, fives))
        assertEquals(StablePlay(PlayZone.SELF, fives), gate.update(PlayZone.SELF, fives))
    }

    @Test
    fun acceptsCorrectedTopReplacementSequenceWithoutMergingDifferentCandidates() {
        val gate = StablePlayGate()
        val sequence = listOf(
            RankCounts.of(mapOf(Rank.KING to 1)),
            RankCounts.of(mapOf(Rank.FIVE to 2)),
            RankCounts.of(mapOf(Rank.SIX to 2)),
            RankCounts.of(mapOf(Rank.TEN to 2)),
            RankCounts.of(mapOf(Rank.JACK to 2)),
            RankCounts.of(mapOf(Rank.SIX to 1))
        )
        val events = sequence.flatMap { cards ->
            listOf(gate.update(PlayZone.TOP, cards), gate.update(PlayZone.TOP, cards))
        }.filterNotNull()
        assertEquals(sequence.map { StablePlay(PlayZone.TOP, it) }, events)
        assertNull(gate.update(PlayZone.TOP, sequence.last()))
        assertNull(gate.update(PlayZone.TOP, sequence.last()))
    }

    @Test
    fun ignoresBriefEmptyAnimationBeforeSameCardsReturn() {
        val gate = StablePlayGate()
        gate.update(PlayZone.RIGHT_TOP, fives)
        assertEquals(StablePlay(PlayZone.RIGHT_TOP, fives), gate.update(PlayZone.RIGHT_TOP, fives))
        repeat(2) { assertNull(gate.update(PlayZone.RIGHT_TOP, RankCounts.zero())) }
        repeat(2) { assertNull(gate.update(PlayZone.RIGHT_TOP, fives)) }
    }

    @Test
    fun newSessionClearsAcceptedPlay() {
        val gate = StablePlayGate()
        gate.update(PlayZone.SELF, fives)
        assertEquals(StablePlay(PlayZone.SELF, fives), gate.update(PlayZone.SELF, fives))
        gate.reset()
        assertNull(gate.update(PlayZone.SELF, fives))
        assertEquals(StablePlay(PlayZone.SELF, fives), gate.update(PlayZone.SELF, fives))
    }

    @Test
    fun restartPrimeSkipsCardsAlreadyOnTable() {
        val gate = StablePlayGate()
        gate.prime(PlayZone.LEFT_TOP, fives)
        repeat(2) { assertNull(gate.update(PlayZone.LEFT_TOP, fives)) }
        repeat(6) { gate.update(PlayZone.LEFT_TOP, RankCounts.zero()) }
        assertNull(gate.update(PlayZone.LEFT_TOP, fives))
        assertEquals(StablePlay(PlayZone.LEFT_TOP, fives), gate.update(PlayZone.LEFT_TOP, fives))
    }

    @Test
    fun resetsUnknownCounterWhenZoneClearsOrRankMatches() {
        assertEquals(1, stableUnknownCount(0, cardsVisible = true, cards = RankCounts.zero()))
        assertFalse(isStableUnknownPlay(2))
        assertTrue(isStableUnknownPlay(3))
        assertEquals(0, stableUnknownCount(1, cardsVisible = false, cards = RankCounts.zero()))
        assertEquals(0, stableUnknownCount(1, cardsVisible = true, cards = fives))
    }

    @Test
    fun rejectsYoloFalsePositiveWithoutWhiteCardArea() {
        assertFalse(isPlayVisible(whiteAreaVisible = false))
        assertTrue(isPlayVisible(whiteAreaVisible = true))
        assertFalse(hasVisibleCardArea(whiteSamples = 0, totalSamples = 144))
        assertTrue(hasVisibleCardArea(whiteSamples = 30, totalSamples = 207))
    }

    @Test
    fun distinguishesGreenGameTableFromWhiteAppScreen() {
        assertTrue(isTableGreen(red = 30, green = 130, blue = 109))
        assertFalse(isTableGreen(red = 245, green = 245, blue = 245))
    }

    @Test
    fun rejectsPublicDetectionAtBottomCropEdge() {
        assertTrue(isPublicRankBand(centerY = 0.50f))
        assertFalse(isPublicRankBand(centerY = 0.97f))
    }

    @Test
    fun keepsPublicPlayProbingDuringSelfHandAnimation() {
        assertTrue(shouldProbeRanks(firstPlayArmed = true, stableHandSamples = 0))
        assertFalse(shouldProbeRanks(firstPlayArmed = false, stableHandSamples = 2))
        assertTrue(shouldProbeRanks(firstPlayArmed = false, stableHandSamples = 3))
    }

    @Test
    fun fastConfirmsOnlyConfidentMultiCardPlays() {
        assertTrue(canFastConfirmPlay(fives, minimumConfidence = 0.60f))
        assertFalse(canFastConfirmPlay(fives, minimumConfidence = 0.59f))
        assertFalse(canFastConfirmPlay(RankCounts.of(mapOf(Rank.TWO to 1)), minimumConfidence = 0.99f))

    }

    @Test
    fun letsTheFullScreenTemplateCorrectOnePublicCard() {
        val king = RankCounts.of(mapOf(Rank.KING to 1))
        val ten = RankCounts.of(mapOf(Rank.TEN to 1))
        assertEquals(king, fuseSinglePublicPlay(ten, king))
        assertEquals(fives, fuseSinglePublicPlay(fives, king))
    }

    @Test
    fun keepsOverlappingPublicCardsSeparate() {
        val matches = listOf(
            HandRankMatcher.Match(100, Rank.SEVEN, 1.0),
            HandRankMatcher.Match(114, Rank.SEVEN, 0.9)
        )

        assertEquals(2, selectDistinctMatches(matches, maxCount = 12, minGap = 10).size)
    }
}
