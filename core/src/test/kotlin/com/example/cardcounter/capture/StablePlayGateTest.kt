package com.example.cardcounter.capture

import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.OutsideSeat
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StablePlayGateTest {
    private val fives = RankCounts.of(mapOf(Rank.FIVE to 4))
    private val three = RankCounts.of(mapOf(Rank.THREE to 1))
    private val jokerNines = RankCounts.of(mapOf(Rank.BIG_JOKER to 1, Rank.NINE to 2))

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
    fun selfZonePlayProducesNoEventAndOutsidePlayCarriesSeat() {
        val cards = RankCounts.of(mapOf(Rank.THREE to 1, Rank.FIVE to 2))

        // 自己出的牌完全不管：不生成任何账本事件。
        assertNull(StablePlay(PlayZone.SELF, cards).toGameEvent())

        val event = StablePlay(PlayZone.LEFT_TOP, cards).toGameEvent()
        assertTrue(event is GameEvent.OutsideCardsPlayed)
        event as GameEvent.OutsideCardsPlayed
        assertEquals(cards, event.cards)
        assertEquals(OutsideSeat.LEFT_TOP, event.seat)
    }

    @Test
    fun sameCardsWithoutTheirTurnAreDuplicates() {
        val gate = StablePlayGate()
        assertEquals(StablePlay(PlayZone.RIGHT_BOTTOM, three), gate.accept(PlayZone.RIGHT_BOTTOM, three))
        assertNull(gate.accept(PlayZone.RIGHT_BOTTOM, three))
        assertNull(gate.accept(PlayZone.RIGHT_BOTTOM, three))
    }

    @Test
    fun sameCardsAfterTheirTurnAreANewHand() {
        val gate = StablePlayGate()
        assertEquals(StablePlay(PlayZone.TOP, fives), gate.accept(PlayZone.TOP, fives))
        gate.noteTurn(PlayZone.TOP)
        assertEquals(StablePlay(PlayZone.TOP, fives), gate.accept(PlayZone.TOP, fives))
        assertNull(gate.accept(PlayZone.TOP, fives))
    }

    @Test
    fun differentCardsAreANewHandEvenWithoutAnEmptyTable() {
        val gate = StablePlayGate()
        assertEquals(StablePlay(PlayZone.RIGHT_BOTTOM, jokerNines), gate.accept(PlayZone.RIGHT_BOTTOM, jokerNines))
        assertEquals(StablePlay(PlayZone.RIGHT_BOTTOM, three), gate.accept(PlayZone.RIGHT_BOTTOM, three))
    }

    @Test
    fun emptyCardsAreNeverAcceptedAndDoNotForgetTheLastHand() {
        val gate = StablePlayGate()
        assertEquals(StablePlay(PlayZone.TOP, three), gate.accept(PlayZone.TOP, three))
        assertNull(gate.accept(PlayZone.TOP, RankCounts.zero()))
        assertNull(gate.accept(PlayZone.TOP, three))
    }

    @Test
    fun aTurnBeforeTheFirstHandChangesNothing() {
        val gate = StablePlayGate()
        gate.noteTurn(PlayZone.TOP)
        assertEquals(StablePlay(PlayZone.TOP, three), gate.accept(PlayZone.TOP, three))
        assertNull(gate.accept(PlayZone.TOP, three))
    }

    @Test
    fun zonesAreIndependent() {
        val gate = StablePlayGate()
        assertEquals(StablePlay(PlayZone.TOP, three), gate.accept(PlayZone.TOP, three))
        assertEquals(StablePlay(PlayZone.LEFT_TOP, three), gate.accept(PlayZone.LEFT_TOP, three))
        gate.noteTurn(PlayZone.TOP)
        assertNull(gate.accept(PlayZone.LEFT_TOP, three))
        assertEquals(StablePlay(PlayZone.TOP, three), gate.accept(PlayZone.TOP, three))
    }

    @Test
    fun newSessionForgetsEverything() {
        val gate = StablePlayGate()
        gate.accept(PlayZone.SELF, fives)
        gate.noteTurn(PlayZone.SELF)
        gate.reset()
        assertEquals(StablePlay(PlayZone.SELF, fives), gate.accept(PlayZone.SELF, fives))
        assertNull(gate.accept(PlayZone.SELF, fives))
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
        val events = sequence.map { gate.accept(PlayZone.TOP, it) }
        assertEquals(sequence.map { StablePlay(PlayZone.TOP, it) }, events)
        assertNull(gate.accept(PlayZone.TOP, sequence.last()))
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
    fun tableVisibleToleratesPartialAnimationOcclusion() {
        assertTrue(isTableVisible { _, _ -> true })

        val threeGreen = TABLE_GREEN_PROBES.take(TABLE_GREEN_MIN_HITS).toSet()
        assertTrue(isTableVisible { x, y -> (x to y) in threeGreen })

        val twoGreen = TABLE_GREEN_PROBES.take(TABLE_GREEN_MIN_HITS - 1).toSet()
        assertFalse(isTableVisible { x, y -> (x to y) in twoGreen })
        assertFalse(isTableVisible { _, _ -> false })
    }

    @Test
    fun rejectsPublicDetectionAtBottomCropEdge() {
        assertTrue(isPublicRankBand(centerY = 0.50f))
        assertFalse(isPublicRankBand(centerY = 0.97f))
    }

    @Test
    fun probesHandOnlyWhenUnconfirmedAndPictureIsStable() {
        assertFalse(shouldProbeHand(handConfirmed = false, stableHandSamples = 2))
        assertTrue(shouldProbeHand(handConfirmed = false, stableHandSamples = 3))
        assertFalse(shouldProbeHand(handConfirmed = true, stableHandSamples = 3))
    }

    @Test
    fun fastConfirmsOnlyConfidentMultiCardPlays() {
        assertTrue(canFastConfirmPlay(fives, minimumConfidence = 0.60f))
        assertFalse(canFastConfirmPlay(fives, minimumConfidence = 0.59f))
        assertFalse(canFastConfirmPlay(RankCounts.of(mapOf(Rank.TWO to 1)), minimumConfidence = 0.99f))
    }
}
