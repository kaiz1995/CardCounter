package com.example.cardcounter.eval

import com.example.cardcounter.capture.FinalizeReason
import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.recognition.Detection
import com.example.cardcounter.recognition.FrameObservation
import com.example.cardcounter.recognition.ZoneObservation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 出牌区新状态机的回放场景（与手机同一套 RoiPlayEventCache + StablePlayGate + TurnTracker）：
 * 每个用例对应这一局出过的一个真实问题，撤掉对应改动就会失败。
 * 参数：停稳 2 次、清桌 2 次，投票取最后 3 帧。
 */
class TurnGateReplayTest {
    private val meta = GameMeta(
        gameId = "g000003-1", video = "source/x.mp4", split = "dev", startMs = 0,
        prePlayEndMs = 0, firstPlayMs = 0, endMs = 0, complete = true,
        selfHand = RankCounts.of(mapOf(Rank.TWO to 2, Rank.KING to 1)), selfHandCount = 3,
        review = "AUTO", notes = ""
    )

    private var clock = 0L

    private fun obs(visible: Boolean, kings: Int, still: Boolean = true, badge: Boolean = false, rank: Rank = Rank.KING) = ZoneObservation(
        PlayZone.TOP, visible, still,
        List(kings) { Detection(rank, 0.9f, 0.3f + 0.2f * it, 0.42f, 0.12f, 0.22f) },
        turnBadge = badge
    )

    private fun frames(count: Int, zone: ZoneObservation) = List(count) {
        clock += 100
        FrameObservation(clock, true, mapOf(PlayZone.TOP to zone))
    }

    /** 整张牌桌不可见的采样（被弹窗、结算画面挡住）：各区都没有观察。 */
    private fun hidden(count: Int) = List(count) {
        clock += 100
        FrameObservation(clock, false, emptyMap())
    }

    private fun replayAudits(
        frames: List<FrameObservation>,
        finalizeFrameCount: Int = 3,
        stableSamples: Int = 2
    ): List<LegacyAudit> {
        val pipeline = LegacyPipeline(
            stableSamples = stableSamples, clearSamples = 2, finalizeFrameCount = finalizeFrameCount
        )
        Replay.run(meta, frames, pipeline) { pipeline.audits }
        return pipeline.audits
    }

    @Test
    fun cardsThatFlashEmptyAndComeBackAreNotCountedAgain() {
        // 3♠ 一直摆着，中间画面闪了一下像空的：同一手不能记两次。
        val result = replayAudits(
            frames(3, obs(true, 1)) + frames(2, obs(false, 0)) + frames(3, obs(true, 1))
        )
        assertEquals(
            listOf(LegacyOutcome.ACCEPTED, LegacyOutcome.DUPLICATE_SUPPRESSED),
            result.map { it.outcome }
        )
    }

    @Test
    fun sameCardsAfterTheirTurnAreCountedAgain() {
        // 同一家先出一张 K，轮到他（圆标亮满 2 次采样）之后又出一张 K：是新的一手。
        val result = replayAudits(
            frames(3, obs(true, 1)) +
                frames(2, obs(false, 0, badge = true)) +
                frames(2, obs(false, 0)) +
                frames(3, obs(true, 1))
        )
        assertEquals(listOf(LegacyOutcome.ACCEPTED, LegacyOutcome.ACCEPTED), result.map { it.outcome })
    }

    @Test
    fun aBadgeOverHangingCardsDoesNotLetTheSameHandCountAgain() {
        // 挂王：上一手还摆着，圆标就亮在旁边；这时画面闪一下再交出同一手，不能记两次。
        val result = replayAudits(
            frames(3, obs(true, 1)) +
                frames(2, obs(true, 1, badge = true)) +
                frames(1, obs(false, 0, badge = true)) +
                frames(3, obs(true, 1, badge = true))
        )
        assertEquals(
            listOf(LegacyOutcome.ACCEPTED, LegacyOutcome.DUPLICATE_SUPPRESSED),
            result.map { it.outcome }
        )
    }

    @Test
    fun aSingleBadgeSampleDoesNotCountAsTheirTurn() {
        val result = replayAudits(
            frames(3, obs(true, 1)) +
                frames(1, obs(false, 0, badge = true)) +
                frames(3, obs(false, 0)) +
                frames(3, obs(true, 1))
        )
        assertEquals(
            listOf(LegacyOutcome.ACCEPTED, LegacyOutcome.DUPLICATE_SUPPRESSED),
            result.map { it.outcome }
        )
    }

    @Test
    fun differentCardsCoveringTheOldOnesWithoutAGapAreCounted() {
        // 挂王“大王+9+9”紧接着 3♠ 直接盖上去，中间桌面没空过：必须各记一手。
        val jokerNines = ZoneObservation(
            PlayZone.TOP, cardVisible = true, still = true,
            detections = listOf(
                Detection(Rank.BIG_JOKER, 0.9f, 0.25f, 0.42f, 0.12f, 0.22f),
                Detection(Rank.NINE, 0.9f, 0.45f, 0.42f, 0.12f, 0.22f),
                Detection(Rank.NINE, 0.9f, 0.65f, 0.42f, 0.12f, 0.22f)
            )
        )
        val three = ZoneObservation(
            PlayZone.TOP, cardVisible = true, still = false,
            detections = listOf(Detection(Rank.THREE, 0.9f, 0.50f, 0.42f, 0.12f, 0.22f))
        )
        val threeStill = three.copy(still = true)
        val result = replayAudits(
            frames(3, jokerNines) + frames(1, three) + frames(2, threeStill)
        )
        assertEquals(listOf(LegacyOutcome.ACCEPTED, LegacyOutcome.ACCEPTED), result.map { it.outcome })
        assertEquals(listOf(3, 1), result.map { it.cards?.total() })
    }

    @Test
    fun cardsPulledAwayBeforeSettlingStillCountAndThenTheSameCardsAfterTheirTurnCountAgain() {
        // 一手牌只摆了不到停稳次数（停稳 2 次，只比较到 1 次静止）就收走：空出来的第一帧起圆标就亮着。
        // 圆标亮满 2 次时这手牌还没兜底交出，不能算轮到他；兜底入账之后才算，这家下一手出一样的牌要入账。
        val result = replayAudits(
            frames(2, obs(true, 1)) +
                frames(4, obs(false, 0, badge = true)) +
                frames(3, obs(true, 1))
        )
        assertEquals(listOf(LegacyOutcome.ACCEPTED, LegacyOutcome.ACCEPTED), result.map { it.outcome })
    }

    @Test
    fun theSampleThatClearsTheZoneAlreadyCountsTowardTheirTurn() {
        // 没停稳就收走：清桌那次采样兜底入账，同一次采样的圆标就算第 1 次，再亮 1 次就轮到他。
        // 圆标只亮 3 次采样（清桌 2 次 + 1 次），这家下一手出一样的牌也要入账。
        val result = replayAudits(
            frames(2, obs(true, 1)) +
                frames(3, obs(false, 0, badge = true)) +
                frames(3, obs(true, 1))
        )
        assertEquals(listOf(LegacyOutcome.ACCEPTED, LegacyOutcome.ACCEPTED), result.map { it.outcome })
    }

    @Test
    fun aCollectFrameBeforeSettlingDoesNotLoseTheHand() {
        // 一手牌没停稳（只静止过 1 次）就收走，收牌动画先拍到一帧变化画面：清桌时仍要兜底记上这一手。
        val result = replayAudits(
            frames(2, obs(true, 1)) + frames(1, obs(true, 2, still = false)) + frames(3, obs(false, 0))
        )
        assertEquals(listOf(LegacyOutcome.ACCEPTED), result.map { it.outcome })
        assertEquals(listOf(1), result.map { it.cards?.total() })
    }

    @Test
    fun handCoveredDirectlyByTheNextHandAfterAShortStayIsStillCounted() {
        // 第一手（这里用 2 张 5）只静止 2 次（约 194ms，正好是回放门槛 coveredStillSamples=2）
        // 没到停稳次数，同一家的下一手（1 张 K，不同主点数）直接盖上：两手都要入账。门槛回退成 3 这条就会失败。
        val result = replayAudits(
            frames(3, obs(true, 2, rank = Rank.FIVE)) +
                frames(1, obs(true, 1, still = false)) +
                frames(2, obs(true, 1)) +
                frames(3, obs(false, 0)),
            stableSamples = 10
        )
        assertEquals(listOf(LegacyOutcome.ACCEPTED, LegacyOutcome.ACCEPTED), result.map { it.outcome })
        assertEquals(listOf(2, 1), result.map { it.cards?.total() })
        assertEquals(listOf(FinalizeReason.COVERED, FinalizeReason.CLEARED), result.map { it.reason })
    }

    @Test
    fun aSlideInThatWasOnlyStillOnceBeforeBeingCoveredIsNotCounted() {
        // 只静止 1 次（滑入动画）就被盖上：不是一手牌。
        val result = replayAudits(
            frames(2, obs(true, 2)) +
                frames(1, obs(true, 1, still = false)) +
                frames(2, obs(true, 1)) +
                frames(3, obs(false, 0)),
            stableSamples = 10
        )
        assertEquals(listOf(LegacyOutcome.ACCEPTED), result.map { it.outcome })
        assertEquals(listOf(1), result.map { it.cards?.total() })
    }

    @Test
    fun lastHandIsCountedWhenTheTableBecomesHiddenAfterAShortStay() {
        // 最后一手只静止 2 次（正好是回放门槛 coveredStillSamples=2）牌桌就被结算画面挡住，各区都没有观察：
        // 这一手要入账。门槛回退成 3 这条就会失败。
        val result = replayAudits(frames(3, obs(true, 2)) + hidden(3), stableSamples = 10)
        assertEquals(listOf(LegacyOutcome.ACCEPTED), result.map { it.outcome })
        assertEquals(listOf(2), result.map { it.cards?.total() })
        assertEquals(listOf(FinalizeReason.TABLE_HIDDEN), result.map { it.reason })
    }

    @Test
    fun aPopupThatBrieflyHidesTheTableDoesNotCountTheSameHandTwice() {
        // 弹窗短挡后同一手还摆着：挡住时交出入账，回来后再停稳，去重门挡住不重记。
        val result = replayAudits(
            frames(4, obs(true, 2)) + hidden(2) + frames(5, obs(true, 2)),
            stableSamples = 4
        )
        assertEquals(
            listOf(LegacyOutcome.ACCEPTED, LegacyOutcome.DUPLICATE_SUPPRESSED),
            result.map { it.outcome }
        )
        assertEquals(listOf(FinalizeReason.TABLE_HIDDEN, FinalizeReason.STABLE), result.map { it.reason })
    }

    @Test
    fun slideInFramesDoNotOutvoteTheSettledPicture() {
        // 牌滑进来的 4 帧（不静止）认成 1 张，停稳段第一帧认成 2 张（还没静止），再 2 帧静止认成 2 张。
        // 投票帧数设得比总帧数大：只交最后一段静止的 3 帧，结果是 2 张，不被 4 帧滑入画面压过。
        val result = replayAudits(
            frames(4, obs(true, 1, still = false)) +
                frames(1, obs(true, 2, still = false)) +
                frames(2, obs(true, 2)),
            finalizeFrameCount = 10
        )
        assertEquals(listOf(2), result.map { it.cards?.total() })
        assertEquals(listOf(3), result.map { it.frameCount })
    }

    @Test
    fun slideInFramesBeforeTheSettledSegmentAreNotKept() {
        // 滑入动画的帧（不静止，认成 3 张）不进入投票：交出的只有停稳段的 3 帧。
        val result = replayAudits(
            frames(2, obs(true, 3, still = false)) +
                frames(1, obs(true, 2, still = false)) +
                frames(2, obs(true, 2)),
            finalizeFrameCount = 10
        )
        assertEquals(listOf(3), result.map { it.frameCount })
        assertEquals(listOf(2), result.map { it.cards?.total() })
    }

    @Test
    fun divergentFramesWithThreeFoursAtHighConfAndFourFoursAtLowConfNeverRecordThreeFours() {
        val threeFours = ZoneObservation(
            PlayZone.LEFT_BOTTOM, cardVisible = true, still = true,
            detections = listOf(
                Detection(Rank.FOUR, 0.75f, 0.22f, 0.55f, 0.07f, 0.09f),
                Detection(Rank.FOUR, 0.80f, 0.30f, 0.55f, 0.07f, 0.09f),
                Detection(Rank.FOUR, 0.78f, 0.38f, 0.55f, 0.07f, 0.09f)
            )
        )
        val fourFours = ZoneObservation(
            PlayZone.LEFT_BOTTOM, cardVisible = true, still = true,
            detections = listOf(
                Detection(Rank.FOUR, 0.76f, 0.221f, 0.55f, 0.07f, 0.09f),
                Detection(Rank.FOUR, 0.81f, 0.301f, 0.55f, 0.07f, 0.09f),
                Detection(Rank.FOUR, 0.77f, 0.381f, 0.55f, 0.07f, 0.09f),
                Detection(Rank.FOUR, 0.28f, 0.46f, 0.55f, 0.07f, 0.09f)
            )
        )
        val pipeline = LegacyPipeline(stableSamples = 2, clearSamples = 2, finalizeFrameCount = 2)
        val replay = Replay.run(
            meta,
            listOf(
                FrameObservation(100, true, mapOf(PlayZone.LEFT_BOTTOM to threeFours)),
                FrameObservation(200, true, mapOf(PlayZone.LEFT_BOTTOM to threeFours)),
                FrameObservation(300, true, mapOf(PlayZone.LEFT_BOTTOM to fourFours))
            ),
            pipeline
        ) { pipeline.audits }
        assertEquals(1, replay.accepted.size)
        assertEquals(RankCounts.of(mapOf(Rank.FOUR to 4)), replay.accepted.single().cards)
    }

    @Test
    fun mixedRanksNeverEnterLedgerWhileWildCombinationsAreAllowed() {
        val mixed = ZoneObservation(
            PlayZone.TOP, cardVisible = true, still = true,
            detections = listOf(
                Detection(Rank.SEVEN, 0.9f, 0.3f, 0.42f, 0.1f, 0.2f),
                Detection(Rank.EIGHT, 0.9f, 0.5f, 0.42f, 0.1f, 0.2f)
            )
        )
        val validWithWilds = ZoneObservation(
            PlayZone.TOP, cardVisible = true, still = true,
            detections = listOf(
                Detection(Rank.BIG_JOKER, 0.9f, 0.2f, 0.42f, 0.1f, 0.2f),
                Detection(Rank.SMALL_JOKER, 0.9f, 0.35f, 0.42f, 0.1f, 0.2f),
                Detection(Rank.TWO, 0.9f, 0.5f, 0.42f, 0.1f, 0.2f),
                Detection(Rank.ACE, 0.9f, 0.65f, 0.42f, 0.1f, 0.2f)
            )
        )
        val result = replayAudits(
            frames(3, mixed) + frames(2, obs(false, 0)) + frames(3, validWithWilds)
        )
        assertEquals(listOf(LegacyOutcome.RULE_SUSPICIOUS, LegacyOutcome.ACCEPTED), result.map { it.outcome })
        assertEquals(4, result[1].cards?.total())
    }

    @Test
    fun subsetAndSupersetFlashWithoutTurnOrClearAreSuppressed() {
        fun fours(count: Int) = ZoneObservation(
            PlayZone.TOP, cardVisible = true, still = true,
            detections = List(count) { Detection(Rank.FOUR, 0.9f, 0.25f + 0.12f * it, 0.42f, 0.1f, 0.2f) }
        )
        val result = replayAudits(
            // 第一次停稳漏成 2 张 4（入账 2 张），闪空 100ms（不够长清桌），再停稳认全 3 张 4：必须拦下不能重扣成 5 张。
            frames(3, fours(2)) + frames(1, obs(false, 0)) + frames(3, fours(3))
        )
        assertEquals(listOf(LegacyOutcome.ACCEPTED, LegacyOutcome.DUPLICATE_SUPPRESSED), result.map { it.outcome })
        assertEquals(2, result.first().cards?.total())
    }

    @Test
    fun fullSessionReplayWithoutPrePlayTrimSkipsExchangePlaysAndCountsAfterOpeningBadge() {
        // 不切 prePlayEndMs（prePlayEndMs=0）的全段回放：未知手牌、开局买牌/交贡阶段无 OUTSIDE 圆标。
        val unconfirmedMeta = meta.copy(prePlayEndMs = 0, selfHand = RankCounts.zero(), selfHandCount = null)
        val exchangePlay = ZoneObservation(
            PlayZone.RIGHT_BOTTOM, cardVisible = true, still = true,
            detections = listOf(Detection(Rank.THREE, 0.9f, 0.5f, 0.5f, 0.1f, 0.2f)),
            turnBadge = false
        )
        val emptyWithBadge = ZoneObservation(PlayZone.RIGHT_BOTTOM, cardVisible = false, still = true, detections = emptyList(), turnBadge = true)
        val emptyNoBadge = ZoneObservation(PlayZone.RIGHT_BOTTOM, cardVisible = false, still = true, detections = emptyList(), turnBadge = false)
        val firstFormalPlay = ZoneObservation(
            PlayZone.RIGHT_BOTTOM, cardVisible = true, still = true,
            detections = listOf(Detection(Rank.KING, 0.9f, 0.5f, 0.5f, 0.1f, 0.2f)),
            turnBadge = false
        )
        val pipeline = LegacyPipeline(stableSamples = 2, clearSamples = 2, finalizeFrameCount = 3, requireOpeningSignal = true)
        val replay = Replay.run(
            unconfirmedMeta,
            frames(3, exchangePlay) +
                frames(2, emptyNoBadge) +
                // 即使中间 SELF 区出现“宣”字误判圆标，也不应提前放行；只有 OUTSIDE 出现圆标才算正式开始。
                listOf(
                    FrameObservation(1000, true, mapOf(PlayZone.SELF to ZoneObservation(PlayZone.SELF, false, true, null, turnBadge = true)))
                ) +
                frames(3, exchangePlay) +
                frames(2, emptyNoBadge) +
                // 正式首出前 OUTSIDE 亮圆标。
                frames(2, emptyWithBadge) +
                frames(3, firstFormalPlay),
            pipeline
        ) { pipeline.audits }

        assertEquals(
            listOf(LegacyOutcome.PRE_PLAY_SKIPPED, LegacyOutcome.PRE_PLAY_SKIPPED, LegacyOutcome.ACCEPTED),
            replay.audits.map { it.outcome }
        )
        assertEquals(1, replay.accepted.size)
        assertEquals(RankCounts.of(mapOf(Rank.KING to 1)), replay.accepted.single().cards)
    }

    @Test
    fun longClearOver1500msUnlocksSameCardsEvenIfTurnBadgeWasMissed() {
        // 300ms 瞬时闪空不解锁，1600ms 长清桌解锁，另一区入账+清桌也解锁。
        val pipeline = LegacyPipeline(stableSamples = 2, clearSamples = 2, finalizeFrameCount = 3)
        val longClearFrames = mutableListOf<FrameObservation>()
        var t = 0L
        fun push(dt: Long, zone: ZoneObservation) {
            t += dt
            longClearFrames += FrameObservation(t, true, mapOf(PlayZone.TOP to zone))
        }
        repeat(3) { push(100, obs(true, 1)) }
        // 仅 300ms 闪空：不够 1500ms。
        repeat(3) { push(100, obs(false, 0)) }
        repeat(3) { push(100, obs(true, 1)) }
        // 真实长清桌：连续空桌 >= 1500ms。
        repeat(17) { push(100, obs(false, 0)) }
        repeat(3) { push(100, obs(true, 1)) }

        Replay.run(meta, longClearFrames, pipeline) { pipeline.audits }
        assertEquals(
            listOf(LegacyOutcome.ACCEPTED, LegacyOutcome.DUPLICATE_SUPPRESSED, LegacyOutcome.ACCEPTED),
            pipeline.audits.map { it.outcome }
        )
    }

    @Test
    fun otherSeatPlayRightAfterAFlashClearDoesNotUnlockTheReappearingOldCards() {
        // 右下先出 5×3 入账 → 右下闪空清桌（约 300ms）→ 上家出 10×3 入账 → 右下 5×3 重现停稳：
        // 清桌标记是粘性的，但清桌时间不足、不能被上家入账解锁，重现的旧牌必须判重复。
        fun zone(zone: PlayZone, rank: Rank?) =
            if (rank == null) {
                ZoneObservation(zone, cardVisible = false, still = true, detections = emptyList())
            } else {
                ZoneObservation(
                    zone, cardVisible = true, still = true,
                    detections = List(3) { Detection(rank, 0.9f, 0.3f + 0.2f * it, 0.42f, 0.12f, 0.22f) }
                )
            }

        fun both(count: Int, rightBottom: Rank?, top: Rank?) = List(count) {
            clock += 100
            FrameObservation(
                clock, true,
                mapOf(
                    PlayZone.RIGHT_BOTTOM to zone(PlayZone.RIGHT_BOTTOM, rightBottom),
                    PlayZone.TOP to zone(PlayZone.TOP, top)
                )
            )
        }

        val result = replayAudits(
            both(3, Rank.FIVE, null) +
                both(2, null, null) +
                both(3, null, Rank.TEN) +
                both(3, Rank.FIVE, null)
        )
        assertEquals(
            listOf(LegacyOutcome.ACCEPTED, LegacyOutcome.ACCEPTED, LegacyOutcome.DUPLICATE_SUPPRESSED),
            result.map { it.outcome }
        )
        assertEquals(listOf(PlayZone.RIGHT_BOTTOM, PlayZone.TOP, PlayZone.RIGHT_BOTTOM), result.map { it.zone })
    }

    @Test
    fun occludedPanelOverOldCardsForTwoSecondsNeverUnlocksReappearingCards() {
        // 旧牌在桌入账 → 面板盖住（tableVisible = false / OCCLUDED）2 秒（超过 1.5s 长清桌和 600ms 阈值）→ 面板撤走旧牌重现停稳：
        // 遮挡期间不推进清桌也不计长清桌，旧牌重现后即使重新停稳交出也必须判 DUPLICATE_SUPPRESSED，绝不重复入账。
        val occludedFrames = List(20) {
            clock += 100
            FrameObservation(clock, tableVisible = false, zones = mapOf(PlayZone.TOP to obs(false, 0)))
        }
        val result = replayAudits(
            frames(3, obs(true, 1)) +
                occludedFrames +
                frames(1, obs(true, 1, still = false)) +
                frames(2, obs(true, 1))
        )
        assertEquals(
            listOf(LegacyOutcome.ACCEPTED, LegacyOutcome.DUPLICATE_SUPPRESSED),
            result.map { it.outcome }
        )
    }
}
