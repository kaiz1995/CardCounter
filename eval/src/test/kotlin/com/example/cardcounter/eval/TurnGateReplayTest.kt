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

    private fun obs(visible: Boolean, kings: Int, still: Boolean = true, badge: Boolean = false) = ZoneObservation(
        PlayZone.TOP, visible, still,
        List(kings) { Detection(Rank.KING, 0.9f, 0.3f + 0.2f * it, 0.42f, 0.12f, 0.22f) },
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
        val result = replayAudits(
            frames(3, obs(true, 1)) + frames(1, obs(true, 2, still = false)) + frames(2, obs(true, 2))
        )
        assertEquals(listOf(LegacyOutcome.ACCEPTED, LegacyOutcome.ACCEPTED), result.map { it.outcome })
        assertEquals(listOf(1, 2), result.map { it.cards?.total() })
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
        // 挂王 BIG_JOKER+FIVE×2（这里用 2 张 K）只静止 2 次（约 194ms，正好是回放门槛 coveredStillSamples=2）
        // 没到停稳次数，同一家的下一手 1 张 K 直接盖上：两手都要入账。门槛回退成 3 这条就会失败。
        val result = replayAudits(
            frames(3, obs(true, 2)) +
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
}
