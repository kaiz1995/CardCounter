package com.example.cardcounter.capture

import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.OutsideSeat
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.vision.ArgbRaster
import com.example.cardcounter.vision.ScreenScale
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

    @Test
    fun checkDoesNotMutateGateUntilCommitAcceptedIsCalled() {
        val gate = StablePlayGate()
        // 账本拒绝（未调 commitAccepted）：再次检查同一手牌依然放行，不被吞掉。
        val first = gate.check(PlayZone.TOP, fives)
        assertTrue(first is PlayGateDecision.Accept)
        val retry = gate.check(PlayZone.TOP, fives)
        assertTrue(retry is PlayGateDecision.Accept)

        // 账本接受后提交门控：再交同一手才拦成 EXACT_DUPLICATE。
        gate.commitAccepted(PlayZone.TOP, fives)
        val duplicate = gate.check(PlayZone.TOP, fives)
        assertEquals(PlayGateDecision.Suppressed(PlaySuppressionReason.EXACT_DUPLICATE), duplicate)
    }

    @Test
    fun subsetOrSupersetWithSameMainRankWithoutUnlockOrClearIsSuppressed() {
        val gate = StablePlayGate()
        val twoFours = RankCounts.of(mapOf(Rank.FOUR to 2))
        val threeFours = RankCounts.of(mapOf(Rank.FOUR to 3))
        val oneFour = RankCounts.of(mapOf(Rank.FOUR to 1))
        val threeFoursWithJoker = RankCounts.of(mapOf(Rank.FOUR to 3, Rank.BIG_JOKER to 1))

        assertEquals(StablePlay(PlayZone.LEFT_BOTTOM, twoFours), gate.accept(PlayZone.LEFT_BOTTOM, twoFours))
        // 没轮到、没清桌时：画面闪一下补全认出 3 张 4 或漏成 1 张 4，不能再扣一次。
        assertEquals(
            PlayGateDecision.Suppressed(PlaySuppressionReason.SUBSET_OR_SUPERSET),
            gate.check(PlayZone.LEFT_BOTTOM, threeFours)
        )
        assertEquals(
            PlayGateDecision.Suppressed(PlaySuppressionReason.SUBSET_OR_SUPERSET),
            gate.check(PlayZone.LEFT_BOTTOM, oneFour)
        )
        assertEquals(
            PlayGateDecision.Suppressed(PlaySuppressionReason.SUBSET_OR_SUPERSET),
            gate.check(PlayZone.LEFT_BOTTOM, threeFoursWithJoker)
        )

        // 主点数不同时放行（例如别人直接用 5 压上）。
        val five = RankCounts.of(mapOf(Rank.FIVE to 2))
        assertTrue(gate.check(PlayZone.LEFT_BOTTOM, five) is PlayGateDecision.Accept)
    }

    @Test
    fun otherSeatAcceptedAfterClearOrLongClearUnlocksSameCards() {
        val gate = StablePlayGate()
        val fourTwo = RankCounts.of(mapOf(Rank.FOUR to 2))
        val fourThree = RankCounts.of(mapOf(Rank.FOUR to 3))
        assertEquals(StablePlay(PlayZone.TOP, fourTwo), gate.accept(PlayZone.TOP, fourTwo))

        // 仅其他座位入账但本区牌尚未清走：防止桌面未收走的旧牌因闪烁重扣，仍不解锁。
        gate.commitAccepted(PlayZone.RIGHT_TOP, RankCounts.of(mapOf(Rank.KING to 1)))
        assertEquals(
            PlayGateDecision.Suppressed(PlaySuppressionReason.EXACT_DUPLICATE),
            gate.check(PlayZone.TOP, fourTwo)
        )

        // 本区清桌后即可解锁（即使圆标漏判，也不吞下一手同牌或同主牌）。
        gate.noteZoneCleared(PlayZone.TOP)
        assertTrue(gate.check(PlayZone.TOP, fourTwo) is PlayGateDecision.Accept)
        assertTrue(gate.check(PlayZone.TOP, fourThree) is PlayGateDecision.Accept)

        // 另一种顺序：先清桌、后别家入账，同样解锁。
        gate.commitAccepted(PlayZone.TOP, fourTwo)
        gate.noteZoneCleared(PlayZone.TOP)
        assertEquals(
            PlayGateDecision.Suppressed(PlaySuppressionReason.EXACT_DUPLICATE),
            gate.check(PlayZone.TOP, fourTwo)
        )
        gate.commitAccepted(PlayZone.LEFT_TOP, RankCounts.of(mapOf(Rank.ACE to 1)))
        assertTrue(gate.check(PlayZone.TOP, fourTwo) is PlayGateDecision.Accept)

        // 本区独立经历 >= 1.5s 长清桌（即使没有别家入账）：也解锁。
        gate.commitAccepted(PlayZone.TOP, fourTwo)
        gate.noteLongClear(PlayZone.TOP)
        assertTrue(gate.check(PlayZone.TOP, fourTwo) is PlayGateDecision.Accept)
    }

    @Test
    fun playZoneVisibilitySupportsDimmedCardsSingleJokerAndRejectsGreenTable() {
        fun frameWithZoneColor(zone: PlayZone, cardColor: Int, bgColor: Int = 0x1e826d, fillFraction: Double = 0.2): ArgbRaster {
            val w = ScreenScale.CANVAS_WIDTH
            val h = ScreenScale.CANVAS_HEIGHT
            val pixels = IntArray(w * h) { bgColor }
            val left = zone.left(w)
            val right = zone.right(w)
            val top = zone.rankY(h)
            val bottom = top + zone.visibilityHeight
            val fillRight = left + ((right - left) * fillFraction).toInt()
            for (y in top..bottom) {
                for (x in left..fillRight) {
                    pixels[y * w + x] = cardColor
                }
            }
            return ArgbRaster(pixels, w, h)
        }

        // 烧牌/开点压暗后的灰白牌面（120, 120, 120）：必须算牌面。
        val dimmed = frameWithZoneColor(PlayZone.TOP, 0x787878, fillFraction = 0.35)
        assertTrue(playZoneVisibility(dimmed)[PlayZone.TOP] == true)

        // 单张大小王（约 14.5% 宽度，轻微偏灰暖白 135, 130, 125）：必须算牌面。
        val singleJoker = frameWithZoneColor(PlayZone.LEFT_BOTTOM, 0x87827d, fillFraction = 0.145)
        assertTrue(playZoneVisibility(singleJoker)[PlayZone.LEFT_BOTTOM] == true)

        // 纯绿桌面（30, 130, 109）：所有区都不算牌面。
        val greenTable = frameWithZoneColor(PlayZone.TOP, 0x1e826d, fillFraction = 1.0)
        assertTrue(playZoneVisibility(greenTable).values.none { it })

        // 太暗的背景（70, 70, 70）：不算牌面。
        val darkBg = frameWithZoneColor(PlayZone.RIGHT_TOP, 0x464646, fillFraction = 1.0)
        assertFalse(playZoneVisibility(darkBg)[PlayZone.RIGHT_TOP] == true)
    }

    @Test
    fun playZoneVisibilitySingleFrameLatencyOn2688x1216() {
        val w = 2_688
        val h = 1_216
        val pixels = IntArray(w * h) { 0x1e826d }
        val raster = ArgbRaster(pixels, w, h)
        repeat(50) { playZoneVisibility(raster) }
        val startNs = System.nanoTime()
        val iterations = 1_000
        repeat(iterations) { playZoneVisibility(raster) }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        val perFrameMs = elapsedMs / iterations
        println("BENCHMARK playZoneVisibility(step=4, 2688x1216): ${"%.4f".format(perFrameMs)} ms/frame ($iterations runs = ${"%.1f".format(elapsedMs)} ms)")
        assertTrue("单帧耗时异常过高: $perFrameMs ms", perFrameMs < 5.0)
    }

    @Test
    fun midGameOpenDetectorChecksOnlyOutsideZonesForConsecutiveFrames() {
        val detector = MidGameOpenDetector(requiredConsecutive = 5)
        detector.reset(checkMidOpen = true)

        // 只有 SELF 中央区可见（交贡/宣点/买牌弹窗）：不算中途打开，而且检测到 OUTSIDE 全空后立即结束检查。
        val selfOnly = mapOf(PlayZone.SELF to true)
        assertEquals(MidGameCheckResult.CLEAR, detector.observe(false, 0, true, selfOnly))

        // 弹窗或飞牌在 OUTSIDE 区闪过 4 帧后恢复空桌：不跳过，结束检查。
        detector.reset(checkMidOpen = true)
        val topVisible = mapOf(PlayZone.TOP to true)
        repeat(4) {
            assertEquals(MidGameCheckResult.PENDING, detector.observe(false, 0, true, topVisible))
        }
        assertEquals(MidGameCheckResult.CLEAR, detector.observe(false, 0, true, emptyMap()))

        // OUTSIDE 区连续 5 帧都看到牌面：判为中途打开 SKIP。
        detector.reset(checkMidOpen = true)
        repeat(4) {
            assertEquals(MidGameCheckResult.PENDING, detector.observe(false, 0, true, topVisible))
        }
        assertEquals(MidGameCheckResult.SKIP, detector.observe(false, 0, true, topVisible))
    }

    @Test
    fun openingPlayGateRequiresHandConfirmedOrOutsideBadge() {
        val gate = OpeningPlayGate()
        gate.reset(alreadyInPlay = false)
        assertFalse(gate.isReady(handConfirmed = false))
        assertTrue(gate.isReady(handConfirmed = true))

        gate.reset(alreadyInPlay = false)
        assertFalse(gate.isReady(handConfirmed = false))
        gate.onOutsideTurnBadge(true)
        assertTrue(gate.isReady(handConfirmed = false))
    }
}
