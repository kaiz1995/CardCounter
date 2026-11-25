package com.example.cardcounter.eval

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

    private fun replayAudits(frames: List<FrameObservation>): List<LegacyAudit> {
        val pipeline = LegacyPipeline(stableSamples = 2, clearSamples = 2, finalizeFrameCount = 3)
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
    fun slideInFramesDoNotOutvoteTheSettledPicture() {
        // 牌还在滑进来时误认成 1 张（起点帧），停稳后的 2 帧认成 2 张：以停稳后的为准。
        val result = replayAudits(
            frames(1, obs(true, 1, still = false)) + frames(2, obs(true, 2))
        )
        assertEquals(listOf(2), result.map { it.cards?.total() })
    }

    @Test
    fun slideInFramesBeforeTheSettledSegmentAreNotKept() {
        // 滑入动画的帧（不静止）不会进入投票：只有最后一段静止的帧参与。
        val slide = frames(3, obs(true, 1, still = false))
        val result = replayAudits(slide + frames(3, obs(true, 2)))
        assertEquals(listOf(2), result.map { it.cards?.total() })
    }
}
