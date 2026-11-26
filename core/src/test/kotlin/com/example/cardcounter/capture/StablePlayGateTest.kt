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
        // 半透明下拉通知栏盖住绿桌时 green 仍在 70+，但红通道被垫高（green - red <= 46），必须判非牌桌绿。
        assertFalse(isTableGreen(red = 31, green = 77, blue = 64))
    }

    @Test
    fun notificationShadeOverlayDoesNotTriggerLongClearOrReAdmitSameHand() {
        val cache = RoiPlayEventCache<Int>(isStill = { a, b -> a == b })
        val tracker = ZoneSignalTracker()
        val gate = StablePlayGate()
        val opening = OpeningPlayGate().apply { reset(alreadyInPlay = true) }
        val hand = RankCounts.of(mapOf(Rank.FOUR to 3))
        var nowMs = 1_000L

        // 1) 正常出牌 FOUR:3 停稳入账
        repeat(16) {
            val finalized = cache.observe(PlayZone.LEFT_BOTTOM, tableVisible = true, cardVisible = true, frame = 1, generation = 0L)
            val sig = tracker.sample(
                PlayZone.LEFT_BOTTOM, badgeShown = false, cleared = cache.isCleared(PlayZone.LEFT_BOTTOM, 0L),
                zoneEmpty = false, tableVisible = true, nowMs = nowMs
            )
            routeZoneSignals(PlayZone.LEFT_BOTTOM, sig, nowMs, gate, opening, handConfirmed = true) { true }
            if (finalized != null) {
                assertTrue(gate.check(PlayZone.LEFT_BOTTOM, hand, nowMs) is PlayGateDecision.Accept)
                gate.commitAccepted(PlayZone.LEFT_BOTTOM, hand, nowMs) { cache.isCleared(it, 0L) }
            }
            nowMs += 50L
        }

        // 2) 下拉通知栏半透明遮罩持续 2.5 秒（50 帧）：探针点绿度被拉低判为 tableVisible=false
        val shadedVisible = isTableVisible { x, _ ->
            val (r, g, b) = if (x < 300 || x > 1000) Triple(31, 77, 64) else Triple(34, 35, 38)
            isTableGreen(r, g, b)
        }
        assertFalse(shadedVisible)
        repeat(50) {
            val finalized = cache.observe(PlayZone.LEFT_BOTTOM, tableVisible = shadedVisible, cardVisible = false, frame = null, generation = 0L)
            assertNull(finalized)
            val sig = tracker.sample(
                PlayZone.LEFT_BOTTOM, badgeShown = false, cleared = cache.isCleared(PlayZone.LEFT_BOTTOM, 0L),
                zoneEmpty = false, tableVisible = shadedVisible, nowMs = nowMs
            )
            assertFalse(sig.any)
            nowMs += 50L
        }

        // 3) 收起通知栏，同一手 FOUR:3 仍在桌上，即使指纹微变再交出，去重门也必须拦住不重记
        val suppressed = gate.check(PlayZone.LEFT_BOTTOM, hand, nowMs)
        assertTrue(suppressed is PlayGateDecision.Suppressed)
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

    @Test
    fun checkDoesNotMutateGateUntilCommitAcceptedIsCalled() {
        val gate = StablePlayGate()
        // 账本拒绝（未调 commitAccepted）：再次检查同一手牌依然放行，不被吞掉。
        val first = gate.check(PlayZone.TOP, fives, 0L)
        assertTrue(first is PlayGateDecision.Accept)
        val retry = gate.check(PlayZone.TOP, fives, 0L)
        assertTrue(retry is PlayGateDecision.Accept)

        // 账本接受后提交门控：再交同一手才拦成 EXACT_DUPLICATE。
        gate.commitAccepted(PlayZone.TOP, fives, 0L) { true }
        val duplicate = gate.check(PlayZone.TOP, fives, 0L)
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
            gate.check(PlayZone.LEFT_BOTTOM, threeFours, 0L)
        )
        assertEquals(
            PlayGateDecision.Suppressed(PlaySuppressionReason.SUBSET_OR_SUPERSET),
            gate.check(PlayZone.LEFT_BOTTOM, oneFour, 0L)
        )
        assertEquals(
            PlayGateDecision.Suppressed(PlaySuppressionReason.SUBSET_OR_SUPERSET),
            gate.check(PlayZone.LEFT_BOTTOM, threeFoursWithJoker, 0L)
        )

        // 主点数不同时放行（例如别人直接用 5 压上）。
        val five = RankCounts.of(mapOf(Rank.FIVE to 2))
        assertTrue(gate.check(PlayZone.LEFT_BOTTOM, five, 0L) is PlayGateDecision.Accept)
    }

    @Test
    fun otherSeatAcceptedAfterClearOrLongClearUnlocksSameCards() {
        val gate = StablePlayGate()
        val fourTwo = RankCounts.of(mapOf(Rank.FOUR to 2))
        val fourThree = RankCounts.of(mapOf(Rank.FOUR to 3))
        assertEquals(StablePlay(PlayZone.TOP, fourTwo), gate.accept(PlayZone.TOP, fourTwo))

        // 仅其他座位入账但本区牌尚未清走：防止桌面未收走的旧牌因闪烁重扣，仍不解锁。
        gate.commitAccepted(PlayZone.RIGHT_TOP, RankCounts.of(mapOf(Rank.KING to 1)), 100L) { true }
        assertEquals(
            PlayGateDecision.Suppressed(PlaySuppressionReason.EXACT_DUPLICATE),
            gate.check(PlayZone.TOP, fourTwo, 100L)
        )

        // 本区之后才清桌：只记清桌，不凭之前的他座入账解锁（旧牌被特效遮挡闪空一下不能解锁）。
        gate.noteZoneCleared(PlayZone.TOP, 200L)
        assertEquals(
            PlayGateDecision.Suppressed(PlaySuppressionReason.EXACT_DUPLICATE),
            gate.check(PlayZone.TOP, fourTwo, 200L)
        )

        // 清桌之后别家再入账（清桌已持续够久、此刻仍清桌）：解锁，同牌或同主牌都算新的一手。
        gate.commitAccepted(PlayZone.LEFT_TOP, RankCounts.of(mapOf(Rank.ACE to 1)), 1_000L) { true }
        assertTrue(gate.check(PlayZone.TOP, fourTwo, 1_000L) is PlayGateDecision.Accept)
        assertTrue(gate.check(PlayZone.TOP, fourThree, 1_000L) is PlayGateDecision.Accept)

        // 另一种顺序：先清桌、后别家入账，同样解锁。
        gate.commitAccepted(PlayZone.TOP, fourTwo, 2_000L) { true }
        gate.noteZoneCleared(PlayZone.TOP, 2_100L)
        assertEquals(
            PlayGateDecision.Suppressed(PlaySuppressionReason.EXACT_DUPLICATE),
            gate.check(PlayZone.TOP, fourTwo, 2_100L)
        )
        gate.commitAccepted(PlayZone.RIGHT_BOTTOM, RankCounts.of(mapOf(Rank.ACE to 1)), 3_000L) { true }
        assertTrue(gate.check(PlayZone.TOP, fourTwo, 3_000L) is PlayGateDecision.Accept)

        // 本区独立经历 >= 1.5s 长清桌（即使没有别家入账）：也解锁。
        gate.commitAccepted(PlayZone.TOP, fourTwo, 4_000L) { true }
        gate.noteLongClear(PlayZone.TOP)
        assertTrue(gate.check(PlayZone.TOP, fourTwo, 4_000L) is PlayGateDecision.Accept)
    }

    @Test
    fun otherSeatAcceptedWhileClearedZoneCardsCameBackDoesNotUnlock() {
        val gate = StablePlayGate()
        assertEquals(StablePlay(PlayZone.TOP, three), gate.accept(PlayZone.TOP, three, 0L))
        gate.noteZoneCleared(PlayZone.TOP, 100L)
        // 清桌标记是粘性的：旧牌闪空后又回来，他座入账时本区已不再清桌，不能解锁。
        gate.commitAccepted(PlayZone.LEFT_TOP, RankCounts.of(mapOf(Rank.ACE to 1)), 5_000L) { false }
        assertEquals(
            PlayGateDecision.Suppressed(PlaySuppressionReason.EXACT_DUPLICATE),
            gate.check(PlayZone.TOP, three, 5_100L)
        )
    }

    @Test
    fun otherSeatAcceptedBeforeMinimumClearDurationDoesNotUnlock() {
        val gate = StablePlayGate()
        assertEquals(StablePlay(PlayZone.TOP, three), gate.accept(PlayZone.TOP, three, 0L))
        gate.noteZoneCleared(PlayZone.TOP, 1_000L)
        val ace = RankCounts.of(mapOf(Rank.ACE to 1))
        // 刚清桌不足阈值时长：不解锁。
        gate.commitAccepted(PlayZone.LEFT_TOP, ace, 1_000L + StablePlayGate.OTHER_SEAT_UNLOCK_MIN_CLEAR_MS - 1) { true }
        assertEquals(
            PlayGateDecision.Suppressed(PlaySuppressionReason.EXACT_DUPLICATE),
            gate.check(PlayZone.TOP, three, 2_000L)
        )
        // 满阈值时长：解锁。
        gate.commitAccepted(PlayZone.RIGHT_TOP, ace, 1_000L + StablePlayGate.OTHER_SEAT_UNLOCK_MIN_CLEAR_MS) { true }
        assertTrue(gate.check(PlayZone.TOP, three, 2_000L) is PlayGateDecision.Accept)
    }

    @Test
    fun playZoneVisibilitySupportsDimmedCardsSingleJokerAndRejectsGreenTable() {
        fun frameWithZoneColor(zone: PlayZone, cardColor: Int, bgColor: Int = 0x1e826d, fillFraction: Double = 0.2): ArgbRaster {
            val w = ScreenScale.CANVAS_WIDTH
            val h = ScreenScale.CANVAS_HEIGHT
            val pixels = IntArray(w * h) { bgColor }
            val left = zone.left(w)
            val right = zone.right(w)
            val crop = zone.yoloCrop(w, h)
            val top = crop.top
            val bottom = crop.top + crop.height - 1
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

    /** 背景铺满 [bg]，在 [zone] 的 yoloCrop 内从左起铺 [fraction] 宽度的白块。 */
    private fun zoneFrameWithWhiteFraction(zone: PlayZone, bg: Int, fraction: Double): ArgbRaster {
        val w = ScreenScale.CANVAS_WIDTH
        val h = ScreenScale.CANVAS_HEIGHT
        val pixels = IntArray(w * h) { bg }
        val crop = zone.yoloCrop(w, h)
        val fillRight = crop.left + ((crop.width - 1) * fraction).toInt()
        for (y in crop.top until crop.top + crop.height) {
            for (x in crop.left..fillRight) pixels[y * w + x] = 0xffffff
        }
        return ArgbRaster(pixels, w, h)
    }

    @Test
    fun whiteCardsJustBelowYoloCropDoNotCountWhenCropItselfIsEmptyGreenTable() {
        val w = ScreenScale.CANVAS_WIDTH
        val h = ScreenScale.CANVAS_HEIGHT
        for (zone in PlayZone.entries.filter { it.source == PlaySource.OUTSIDE }) {
            val pixels = IntArray(w * h) { 0x1e826d }
            val crop = zone.yoloCrop(w, h)
            // yoloCrop 正下方 40 行铺满白色（进贡小牌、人物衣领），crop 内部是空绿桌。
            val belowTop = crop.top + crop.height
            val belowBottom = minOf(h - 1, belowTop + 39)
            for (y in belowTop..belowBottom) {
                for (x in crop.left until crop.left + crop.width) pixels[y * w + x] = 0xffffff
            }
            val raster = ArgbRaster(pixels, w, h)
            assertFalse("$zone isZoneCardVisible", isZoneCardVisible(zone) { x, y -> raster.rgb(x, y) })
            assertFalse("$zone playZoneVisibility", playZoneVisibility(raster)[zone] == true)
        }
    }

    @Test
    fun darkPanelBackgroundWithWhiteBlockIsNotACardOnOutsideZones() {
        // 「王 3 / 送 3」压暗面板：ROI 内非白背景中值约 (3, 18, 15)，没有绿桌，白字白块不能当牌，且三态必须判为 OCCLUDED。
        val dark = zoneFrameWithWhiteFraction(PlayZone.RIGHT_BOTTOM, 0x03120f, 0.18)
        assertEquals(ZoneVisibility.OCCLUDED, zoneVisibility(PlayZone.RIGHT_BOTTOM) { x, y -> dark.rgb(x, y) })
        assertEquals(ZoneVisibility.OCCLUDED, playZoneStates(dark)[PlayZone.RIGHT_BOTTOM])
        assertFalse(isZoneCardVisible(PlayZone.RIGHT_BOTTOM) { x, y -> dark.rgb(x, y) })
        assertFalse(playZoneVisibility(dark)[PlayZone.RIGHT_BOTTOM] == true)
    }

    @Test
    fun normalAndDimmedTableGreenWithWhiteBlockIsACardOnOutsideZones() {
        // 正常绿桌 (20, 95, 75) 与烧牌/开点 0.55 倍压暗绿桌 (11, 52, 41)：15% 白块必须算牌。
        for (bg in intArrayOf(0x145f4b, 0x0b3429)) {
            val frame = zoneFrameWithWhiteFraction(PlayZone.RIGHT_BOTTOM, bg, 0.15)
            assertEquals(ZoneVisibility.CARD, zoneVisibility(PlayZone.RIGHT_BOTTOM) { x, y -> frame.rgb(x, y) })
            assertEquals(ZoneVisibility.CARD, playZoneStates(frame)[PlayZone.RIGHT_BOTTOM])
            assertTrue(isZoneCardVisible(PlayZone.RIGHT_BOTTOM) { x, y -> frame.rgb(x, y) })
            assertTrue(playZoneVisibility(frame)[PlayZone.RIGHT_BOTTOM] == true)
        }
        // 纯绿空桌：返回 EMPTY。
        val emptyGreen = zoneFrameWithWhiteFraction(PlayZone.RIGHT_BOTTOM, 0x1e826d, 0.0)
        assertEquals(ZoneVisibility.EMPTY, playZoneStates(emptyGreen)[PlayZone.RIGHT_BOTTOM])
    }

    @Test
    fun selfZoneIsNotSubjectToLocalTableGreenCheckAndKeepsNarrowVisibilityBand() {
        val dark = zoneFrameWithWhiteFraction(PlayZone.SELF, 0x03120f, 0.18)
        assertEquals(ZoneVisibility.CARD, playZoneStates(dark)[PlayZone.SELF])
        assertTrue(playZoneVisibility(dark)[PlayZone.SELF] == true)

        // SELF 区只查 rankY..rankY+visibilityHeight 窄判定带：白块落在 yoloCrop 下方（判定带之外）不算可见。
        val w = ScreenScale.CANVAS_WIDTH
        val h = ScreenScale.CANVAS_HEIGHT
        val pixels = IntArray(w * h) { 0x1e826d }
        val crop = PlayZone.SELF.yoloCrop(w, h)
        val bandBottom = PlayZone.SELF.rankY(h) + PlayZone.SELF.visibilityHeight
        for (y in (bandBottom + 1) until (crop.top + crop.height)) {
            for (x in crop.left until (crop.left + crop.width)) {
                pixels[y * w + x] = 0xffffff
            }
        }
        val outsideBand = ArgbRaster(pixels, w, h)
        assertEquals(ZoneVisibility.EMPTY, playZoneStates(outsideBand)[PlayZone.SELF])
        assertFalse(isZoneCardVisible(PlayZone.SELF) { x, y -> outsideBand.rgb(x, y) })
    }

    @Test
    fun looseTableGreenAcceptsDimmedTableAndRejectsDarkPanel() {
        assertTrue(isDimmedOrNormalTableGreen(20, 95, 75))
        assertTrue(isDimmedOrNormalTableGreen(11, 52, 41))
        assertFalse(isDimmedOrNormalTableGreen(3, 18, 15))
        assertTrue(hasLocalTableGreen(greenSamples = 12, totalSamples = 100))
        assertFalse(hasLocalTableGreen(greenSamples = 11, totalSamples = 100))
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
        val outsideZones = PlayZone.entries.filter { it.source == PlaySource.OUTSIDE }
        val allEmpty = outsideZones.associateWith { ZoneVisibility.EMPTY }
        detector.reset(checkMidOpen = true)

        // 只有 SELF 中央区可见（交贡/宣点/买牌弹窗）：不算中途打开，而且检测到 OUTSIDE 全空后立即结束检查。
        val selfOnly = allEmpty + (PlayZone.SELF to ZoneVisibility.CARD)
        assertEquals(MidGameCheckResult.CLEAR, detector.observe(false, 0, true, selfOnly))

        // 弹窗或飞牌在 OUTSIDE 区闪过 4 帧后恢复空桌：不跳过，结束检查。
        detector.reset(checkMidOpen = true)
        val topVisible = allEmpty + (PlayZone.TOP to ZoneVisibility.CARD)
        repeat(4) {
            assertEquals(MidGameCheckResult.PENDING, detector.observe(false, 0, true, topVisible))
        }
        assertEquals(MidGameCheckResult.CLEAR, detector.observe(false, 0, true, allEmpty))

        // OUTSIDE 区连续 5 帧都看到牌面：判为中途打开 SKIP。
        detector.reset(checkMidOpen = true)
        repeat(4) {
            assertEquals(MidGameCheckResult.PENDING, detector.observe(false, 0, true, topVisible))
        }
        assertEquals(MidGameCheckResult.SKIP, detector.observe(false, 0, true, topVisible))

        // 存在 OCCLUDED 时不能当成“全空桌”提前把 pending 置 false；只有全 EMPTY 才结束检查。
        detector.reset(checkMidOpen = true)
        val allOccluded = outsideZones.associateWith { ZoneVisibility.OCCLUDED }
        val mixedOccluded = outsideZones.associateWith {
            if (it == PlayZone.TOP) ZoneVisibility.OCCLUDED else ZoneVisibility.EMPTY
        }
        assertEquals(MidGameCheckResult.PENDING, detector.observe(false, 0, true, allOccluded))
        assertEquals(MidGameCheckResult.PENDING, detector.observe(false, 0, true, mixedOccluded))
        val topCard = outsideZones.associateWith {
            if (it == PlayZone.TOP) ZoneVisibility.CARD else ZoneVisibility.OCCLUDED
        }
        repeat(4) {
            assertEquals(MidGameCheckResult.PENDING, detector.observe(false, 0, true, topCard))
        }
        assertEquals(MidGameCheckResult.SKIP, detector.observe(false, 0, true, topCard))

        detector.reset(checkMidOpen = true)
        assertEquals(MidGameCheckResult.PENDING, detector.observe(false, 0, true, mixedOccluded))
        assertEquals(MidGameCheckResult.CLEAR, detector.observe(false, 0, true, allEmpty))
    }

    @Test
    fun openingPlayGateRequiresHandConfirmedOrOutsideBadge() {
        val gate = OpeningPlayGate()
        gate.reset(alreadyInPlay = false)
        assertFalse(gate.isReady(handConfirmed = false))
        assertTrue(gate.isReady(handConfirmed = true))

        gate.reset(alreadyInPlay = false)
        assertFalse(gate.isReady(handConfirmed = false))
        gate.onOutsideTurn(turned = true, nowMs = 0L, isOnTable = { false })
        assertTrue(gate.isReady(handConfirmed = false))
    }

    @Test
    fun otherSeatAcceptedAfter150MsClearUnlocksZone() {
        val gate = StablePlayGate()
        val king = RankCounts.of(mapOf(Rank.KING to 1))
        val ace = RankCounts.of(mapOf(Rank.ACE to 1))
        assertEquals(StablePlay(PlayZone.LEFT_TOP, king), gate.accept(PlayZone.LEFT_TOP, king, 0L))
        gate.noteZoneCleared(PlayZone.LEFT_TOP, 1_000L)
        // 清桌后 149ms（不足 150ms）不解锁；满 155ms（>= 150ms）他座入账即解锁。
        gate.commitAccepted(PlayZone.RIGHT_TOP, ace, 1_149L) { true }
        assertTrue(gate.check(PlayZone.LEFT_TOP, king, 1_200L) is PlayGateDecision.Suppressed)
        gate.commitAccepted(PlayZone.RIGHT_BOTTOM, ace, 1_155L) { true }
        assertTrue(gate.check(PlayZone.LEFT_TOP, king, 1_300L) is PlayGateDecision.Accept)
    }

    @Test
    fun coveredPlayUnlocksDirectCoverWithCoreFingerprintChangeAndKeepsEdgeFlickerBlocked() {
        val baseFp = IntArray(RoiFingerprint.COLUMNS * RoiFingerprint.ROWS) { 0x303030 }
        val bottomFlickerFp = baseFp.copyOf().also { arr ->
            for (r in 12..15) for (c in 0 until 20) arr[r * RoiFingerprint.COLUMNS + c] = 0x303030 + (100 shl 16)
        }
        val newCardCoreFp = baseFp.copyOf().also { arr ->
            for (r in 9..10) for (c in 0 until 4) arr[r * RoiFingerprint.COLUMNS + c] = 0x303030 + (100 shl 16)
        }
        val twoSixes = RankCounts.of(mapOf(Rank.SIX to 2))
        val oneKing = RankCounts.of(mapOf(Rank.KING to 1))
        val twoKings = RankCounts.of(mapOf(Rank.KING to 2))

        // 1) 若重交仅底部“少于10张”光效变化，核心区未变，仍被 EXACT/SUBSET 拦截。
        val gate1 = StablePlayGate()
        assertTrue(gate1.check(PlayZone.TOP, twoSixes, 1_000L, fingerprint = baseFp) is PlayGateDecision.Accept)
        gate1.commitAccepted(PlayZone.TOP, twoSixes, 1_000L, fingerprint = baseFp) { false }
        assertEquals(
            PlayGateDecision.Suppressed(PlaySuppressionReason.EXACT_DUPLICATE),
            gate1.check(PlayZone.TOP, twoSixes, 1_300L, fingerprint = bottomFlickerFp)
        )
        // 2) 若核心区 (row 2..11) 确实发生变化，直接盖牌同点连出（EXACT 和 SUBSET）均应放行。
        assertTrue(gate1.check(PlayZone.TOP, twoSixes, 1_500L, fingerprint = newCardCoreFp) is PlayGateDecision.Accept)

        val gate2 = StablePlayGate()
        assertTrue(gate2.check(PlayZone.TOP, oneKing, 1_000L, fingerprint = baseFp) is PlayGateDecision.Accept)
        gate2.commitAccepted(PlayZone.TOP, oneKing, 1_000L, fingerprint = baseFp) { false }
        assertTrue(gate2.check(PlayZone.TOP, twoKings, 1_400L, fingerprint = newCardCoreFp) is PlayGateDecision.Accept)

        // 3) STABLE 满帧交出后同样遵循核心区指纹变化规则：边缘光效/3点微扰拦截，核心区 >= 4 点换花色放行。
        val minorCoreJitterFp = baseFp.copyOf().also { arr ->
            for (c in 0 until 3) arr[5 * RoiFingerprint.COLUMNS + c] = 0x303030 + (100 shl 16)
        }
        val gate3 = StablePlayGate()
        gate3.commitAccepted(PlayZone.TOP, twoSixes, 1_000L, fingerprint = baseFp) { false }
        assertEquals(
            PlayGateDecision.Suppressed(PlaySuppressionReason.EXACT_DUPLICATE),
            gate3.check(PlayZone.TOP, twoSixes, 1_800L, fingerprint = minorCoreJitterFp)
        )
        assertTrue(gate3.check(PlayZone.TOP, twoSixes, 1_900L, fingerprint = newCardCoreFp) is PlayGateDecision.Accept)
    }
}
