package com.example.cardcounter.capture

import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.vision.YoloDetection
import kotlin.math.abs

/**
 * 出牌区多帧投票结果：
 * - [cards] 最终认定的点数合计；
 * - [slotDetections] 合并后每个牌位留下的检测框（单帧共识或各帧一致时为对应帧的框）；
 * - [framesUsed] 参与判定的非空检测帧数；
 * - [mergedFromDisagreement] 多帧之间有分歧、通过按位置聚成牌位合并得出。
 */
data class PlayVoteOutcome(
    val cards: RankCounts,
    val slotDetections: List<YoloDetection>,
    val framesUsed: Int,
    val mergedFromDisagreement: Boolean
)

/**
 * 出牌区多帧投票与牌位合并（坐标为 320×320 模型输入画布像素）：
 * - 没有非空检测帧：返回 null（无共识）。
 * - 只有 1 帧有检测框：只有满足 [canFastConfirmPlay]（>=3 张且最低把握度 >=0.60）才确认，否则返回 null。
 * - 有 2 帧及以上有检测框：
 *   - 若所有非空帧认出的 [RankCounts] 完全一致，直接确认该点数（不走单帧快速确认）；
 *   - 若几帧之间有分歧（例如一帧漏掉低把握的 4 得 3 张 4、把握度都高，另一帧认全 4 张 4、最低把握度 0.28），
 *     禁止靠 [canFastConfirmPlay] 挑单帧入账，而是把各帧检测框按位置聚成牌位合并（同一帧一个牌位最多认领一次，
 *     同一牌位在不同帧认成不同点数时取把握度最高者），避免低把握边缘牌时有时无导致少记。
 */
object PlayFrameVote {
    /** 出牌区停稳帧间同一牌位中心 X/Y 抖动容差（320 画布像素）。 */
    const val X_TOLERANCE = 8f
    const val ROW_TOLERANCE = 12f

    fun resolve(
        frameDetections: List<List<YoloDetection>>,
        xTolerance: Float = X_TOLERANCE,
        rowTolerance: Float = ROW_TOLERANCE
    ): PlayVoteOutcome? {
        val nonEmpty = frameDetections.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return null

        if (nonEmpty.size == 1) {
            val single = nonEmpty.single()
            val cards = countsOf(single)
            val minConfidence = single.minOf { it.confidence }
            return if (canFastConfirmPlay(cards, minConfidence)) {
                PlayVoteOutcome(cards, single, framesUsed = 1, mergedFromDisagreement = false)
            } else {
                null
            }
        }

        val frameCounts = nonEmpty.map(::countsOf)
        if (frameCounts.distinct().size == 1) {
            return PlayVoteOutcome(
                cards = frameCounts.first(),
                slotDetections = nonEmpty.last(),
                framesUsed = nonEmpty.size,
                mergedFromDisagreement = false
            )
        }

        val mergedSlots = mergeDetectionSlots(nonEmpty, xTolerance, rowTolerance)
        val mergedCards = countsOf(mergedSlots)
        return PlayVoteOutcome(
            cards = mergedCards,
            slotDetections = mergedSlots,
            framesUsed = nonEmpty.size,
            mergedFromDisagreement = true
        )
    }

    /** 把多帧检测框按位置聚成牌位：每帧内按置信度从高到低认领牌位，同帧内同一牌位不重复认领，跨帧取置信度最高者。 */
    fun mergeDetectionSlots(
        frames: List<List<YoloDetection>>,
        xTolerance: Float = X_TOLERANCE,
        rowTolerance: Float = ROW_TOLERANCE
    ): List<YoloDetection> {
        val slots = ArrayList<Slot>()
        for (detections in frames) {
            val claimed = HashSet<Slot>()
            for (detection in detections.sortedByDescending { it.confidence }) {
                val slot = slots
                    .filter {
                        it !in claimed &&
                            abs(it.detection.centerY - detection.centerY) <= rowTolerance &&
                            abs(it.detection.centerX - detection.centerX) <= xTolerance
                    }
                    .minByOrNull { abs(it.detection.centerX - detection.centerX) }
                if (slot == null) {
                    claimed += Slot(detection).also { slots += it }
                } else {
                    claimed += slot
                    if (detection.confidence > slot.detection.confidence) {
                        slot.detection = detection
                    }
                }
            }
        }
        return slots.map { it.detection }
    }

    fun countsOf(detections: List<YoloDetection>): RankCounts {
        if (detections.isEmpty()) return RankCounts.zero()
        val counts = HashMap<Rank, Int>()
        detections.forEach { counts[it.rank] = (counts[it.rank] ?: 0) + 1 }
        return RankCounts.of(counts)
    }

    private class Slot(var detection: YoloDetection)
}
