package com.example.cardcounter.capture

import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.vision.YoloDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandFrameMergerTest {
    private fun det(rank: Rank, x: Float, y: Float, conf: Float = 0.9f) =
        YoloDetection(rank, rank.name, conf, x, y, 7f, 10.5f)

    /** 一排牌：从 startX 起每张相隔 16（和真机 320 画布上的间距一样）。 */
    private fun row(y: Float, startX: Float, vararg ranks: Pair<Rank, Int>, shiftX: Float = 0f): List<YoloDetection> {
        val result = ArrayList<YoloDetection>()
        var index = 0
        for ((rank, count) in ranks) repeat(count) {
            result += det(rank, startX + 16f * index + shiftX, y)
            index += 1
        }
        return result
    }

    private val topRow = 222f
    private val bottomRow = 244.5f

    /** 事故那一手：三个 4，共 35 张（上排 17、下排 18）；中间那张 4 固定在下排第 16 张（序号 15）。 */
    private fun accidentHand(middleFourConfidence: Float?, shiftX: Float = 0f): List<YoloDetection> {
        val top = row(
            topRow, 20f, Rank.BIG_JOKER to 2, Rank.TWO to 2, Rank.ACE to 3, Rank.KING to 3,
            Rank.QUEEN to 3, Rank.JACK to 2, Rank.TEN to 2, shiftX = shiftX
        )
        val bottom = row(
            bottomRow, 13f, Rank.TEN to 2, Rank.NINE to 2, Rank.EIGHT to 1, Rank.SEVEN to 3,
            Rank.SIX to 4, Rank.FIVE to 2, Rank.FOUR to 3, Rank.THREE to 1, shiftX = shiftX
        )
        val all = top + bottom
        val middleFour = bottom[15]
        return all.mapNotNull {
            if (it !== middleFour) it
            else middleFourConfidence?.let { conf -> it.copy(confidence = conf) }
        }
    }

    private fun counts(vararg pairs: Pair<Rank, Int>) = RankCounts.of(pairs.toMap())

    @Test
    fun accidentOneFrameWithThreeFoursAndOneWithTwoMergeToThreeFours() {
        val merger = HandFrameMerger()
        val withFour = merger.add(1, accidentHand(middleFourConfidence = 0.30f))
        assertEquals(35, withFour.single.total())
        assertEquals(3, withFour.single[Rank.FOUR])

        // 下一帧这张 4 掉到门槛以下，被模型漏掉，整体还有 1 像素的位置抖动。
        val withoutFour = merger.add(1, accidentHand(middleFourConfidence = null, shiftX = 1f))
        assertEquals(34, withoutFour.single.total())
        assertEquals(2, withoutFour.single[Rank.FOUR])
        assertEquals(35, withoutFour.merged.total())
        assertEquals(3, withoutFour.merged[Rank.FOUR])
        assertEquals(2, withoutFour.frames)
        assertFalse(withoutFour.fellBack)
        assertTrue(withoutFour.gainedCards)
    }

    @Test
    fun mergeDoesNotDependOnFrameOrder() {
        val merger = HandFrameMerger()
        merger.add(1, accidentHand(middleFourConfidence = null))
        val result = merger.add(1, accidentHand(middleFourConfidence = 0.30f, shiftX = -1.5f))
        assertEquals(35, result.merged.total())
        assertEquals(3, result.merged[Rank.FOUR])
    }

    @Test
    fun slotWithConflictingRanksTakesTheMostConfident() {
        val merger = HandFrameMerger()
        merger.add(1, listOf(det(Rank.SEVEN, 100f, topRow, conf = 0.40f)))
        val result = merger.add(1, listOf(det(Rank.EIGHT, 101f, topRow, conf = 0.80f)))
        assertEquals(counts(Rank.EIGHT to 1), result.merged)
        // 顺序反过来、后来的把握度低：仍然保留把握度高的。
        val reverse = HandFrameMerger()
        reverse.add(1, listOf(det(Rank.EIGHT, 100f, topRow, conf = 0.80f)))
        val second = reverse.add(1, listOf(det(Rank.SEVEN, 99f, topRow, conf = 0.40f)))
        assertEquals(counts(Rank.EIGHT to 1), second.merged)
        assertEquals(0.80f, second.slotDetections.single().confidence, 0f)
    }

    @Test
    fun sceneChangeClearsAccumulationAndKeepsOldSlotsOut() {
        val merger = HandFrameMerger()
        merger.add(1, accidentHand(middleFourConfidence = 0.30f))
        // 画面变了（例如交贡后理牌）：旧画面里的牌位一张也不能带进来。
        val next = merger.add(2, listOf(det(Rank.ACE, 20f, topRow), det(Rank.KING, 36f, topRow)))
        assertEquals(counts(Rank.ACE to 1, Rank.KING to 1), next.merged)
        assertEquals(1, next.frames)
        assertFalse(next.gainedCards)
    }

    @Test
    fun resetClearsAccumulation() {
        val merger = HandFrameMerger()
        merger.add(1, listOf(det(Rank.ACE, 20f, topRow)))
        merger.reset()
        val result = merger.add(1, listOf(det(Rank.KING, 100f, topRow)))
        assertEquals(counts(Rank.KING to 1), result.merged)
    }

    @Test
    fun twoRowsAtTheSameXAreDifferentSlots() {
        val merger = HandFrameMerger()
        merger.add(1, listOf(det(Rank.FIVE, 100f, topRow)))
        val result = merger.add(1, listOf(det(Rank.SIX, 100f, bottomRow)))
        assertEquals(counts(Rank.FIVE to 1, Rank.SIX to 1), result.merged)
        assertEquals(2, result.slotDetections.size)
    }

    @Test
    fun adjacentCardsInOneFrameNeverShareASlot() {
        val merger = HandFrameMerger()
        merger.add(1, listOf(det(Rank.SIX, 100f, topRow)))
        val result = merger.add(1, listOf(det(Rank.SIX, 105f, topRow, conf = 0.9f), det(Rank.SIX, 111f, topRow, conf = 0.8f)))
        assertEquals(2, result.merged[Rank.SIX])
    }

    @Test
    fun overInventoryFallsBackToTheLatestFrameAndRestartsFromIt() {
        val merger = HandFrameMerger()
        // 3 只有 6 张：两帧各 4 张但位置错开 8 像素（超过容差），合并会得到 8 张，必须退回。
        val first = (0 until 4).map { det(Rank.THREE, 10f + 16f * it, topRow) }
        val second = (0 until 4).map { det(Rank.THREE, 18f + 16f * it, topRow) }
        merger.add(1, first)
        val fallback = merger.add(1, second)
        assertTrue(fallback.fellBack)
        assertEquals(counts(Rank.THREE to 4), fallback.merged)
        assertEquals(second, fallback.slotDetections)
        assertFalse(fallback.gainedCards)

        // 之后从最新一帧重新累积：同样位置的下一帧正常合并，不再退回。
        val after = merger.add(1, second)
        assertFalse(after.fellBack)
        assertEquals(counts(Rank.THREE to 4), after.merged)
        assertEquals(2, after.frames)
    }

    @Test
    fun emptyFrameKeepsWhatWasAccumulated() {
        val merger = HandFrameMerger()
        merger.add(1, listOf(det(Rank.ACE, 20f, topRow)))
        val result = merger.add(1, emptyList())
        assertEquals(counts(Rank.ACE to 1), result.merged)
        assertEquals(0, result.single.total())
    }

    @Test
    fun sampleTriggersOnGainOrLowConfidenceOnly() {
        val merger = HandFrameMerger()
        val sure = merger.add(1, listOf(det(Rank.ACE, 20f, topRow, conf = 0.8f)))
        assertTrue(sure.sampleTriggers().isEmpty())

        val low = merger.add(1, listOf(det(Rank.ACE, 20f, topRow, conf = 0.7f), det(Rank.KING, 36f, topRow, conf = 0.3f)))
        assertEquals(listOf(HandSampleTrigger.LOW_CONFIDENCE), low.sampleTriggers())

        // 低把握的 K 这一帧没认出来：合并比单帧多一张；这一帧自己没有低把握的框，只触发“合并多出牌”。
        val gain = merger.add(1, listOf(det(Rank.ACE, 20f, topRow, conf = 0.7f)))
        assertEquals(listOf(HandSampleTrigger.MERGE_GAIN), gain.sampleTriggers())

        // 这一帧又有低把握的框、又比单帧多出牌：两个原因都在。
        val both = merger.add(1, listOf(det(Rank.ACE, 20f, topRow, conf = 0.4f)))
        assertEquals(listOf(HandSampleTrigger.MERGE_GAIN, HandSampleTrigger.LOW_CONFIDENCE), both.sampleTriggers())
    }

    @Test
    fun throttleAdmitsEachReasonOncePerSceneAndCapsPerGame() {
        val throttle = HandSampleThrottle(maxPerGame = 3)
        val low = listOf(HandSampleTrigger.LOW_CONFIDENCE)
        val gain = listOf(HandSampleTrigger.MERGE_GAIN)
        assertTrue(throttle.admit(1, low))
        assertFalse(throttle.admit(1, low))
        // 同一画面里出现新的原因，还可以再存一次。
        assertTrue(throttle.admit(1, low + gain))
        assertFalse(throttle.admit(1, low + gain))
        // 画面变了，原因重新计。
        assertTrue(throttle.admit(2, low))
        // 每局上限 3 份。
        assertFalse(throttle.admit(3, low))
        throttle.reset()
        assertTrue(throttle.admit(3, low))
    }
}
