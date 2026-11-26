package com.example.cardcounter.capture

import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.recognition.GoujiPlayRules
import com.example.cardcounter.recognition.PlayRuleCheck
import com.example.cardcounter.vision.YoloDetection
import kotlin.math.abs

/**
 * 出牌区多帧投票结果：
 * - [cards] 最终认定的点数合计；
 * - [slotDetections] 合并后每个牌位留下的检测框（单帧共识或各帧一致时为对应帧的框）；
 * - [framesUsed] 参与判定的非空检测帧数；
 * - [mergedFromDisagreement] 多帧之间有分歧、通过按位置聚成牌位合并得出；
 * - [fellBackToFrame] 合并结果过不了出牌规则，退回了出现 >= 2 帧且能通过规则的那一帧结果。
 */
data class PlayVoteOutcome(
    val cards: RankCounts,
    val slotDetections: List<YoloDetection>,
    val framesUsed: Int,
    val mergedFromDisagreement: Boolean,
    val fellBackToFrame: Boolean = false
)

/**
 * 出牌区多帧投票与牌位合并（坐标为 320×320 模型输入画布像素）：
 * - 没有非空检测帧：返回 null（无共识）。
 * - 只有 1 帧有检测框：只有满足 [canFastConfirmPlay]（>=3 张且最低把握度 >=0.60）才确认，否则返回 null。
 * - 有 2 帧及以上有检测框：
 *   - 若所有非空帧认出的 [RankCounts] 完全一致，直接确认该点数（不走单帧快速确认）；
 *   - 若几帧之间有分歧（例如一帧漏掉低把握的 4 得 3 张 4、把握度都高，另一帧认全 4 张 4、最低把握度 0.28），
 *     禁止靠 [canFastConfirmPlay] 挑单帧入账，而是把各帧检测框按位置聚成牌位合并（同一帧一个牌位最多认领一次，
 *     同一牌位在不同帧认成不同点数时取把握度最高者），避免低把握边缘牌时有时无导致少记；
 *   - 停稳帧 >= [OUTLIER_FILTER_MIN_FRAMES] 时，只在 1 帧出现的牌位要同时满足“点数等于多帧牌位的主点数或是配牌”和
 *     “与已保留的牌位横向相邻”才保留，单帧低把握的假阳性和远处的离群框不再被并集吸进来；只有 2 帧时保持并集；
 *   - 合并结果过不了 [GoujiPlayRules]（混点数、超库存）时，退回出现 >= [FALLBACK_MIN_FRAMES] 帧且能通过规则的那一帧结果；
 *     都没有才原样交出合并结果，由调用方按规则拦下并存样本。
 *
 * 牌位匹配按检测框自身尺寸，不用固定像素：各出牌区裁剪放大到 320 画布的倍数不同（顶部约 3.9 倍、右侧约 2.3 倍、
 * 左上约 2.1 倍、左下约 1.8 倍），固定像素容差在放大多的区里只相当于原画布两三个像素，轻微抖动就会把同一张牌拆成两张。
 * 这里用“两框中心横向差 <= 0.5×较小宽、纵向差 <= 0.5×较小高”：容差随放大倍数自动缩放，
 * 且小于一张牌的间距（相邻两张牌中心相距远大于框宽的一半），不会把相邻两张并成一张。
 * 没有选交并比：同一牌位的框抖动时交并比对小框很敏感，中心位移比例更直观，也与调用方的“同帧一个牌位只认领一次”配合得更稳。
 */
object PlayFrameVote {
    /** 同一牌位：中心差不超过较小框宽/高的这个比例。 */
    const val CENTER_TOLERANCE_RATIO = 0.5f

    /** 停稳帧达到这个数才过滤只在 1 帧出现的牌位。 */
    const val OUTLIER_FILTER_MIN_FRAMES = 3

    /** 单帧牌位与已保留牌位横向相邻：中心距不超过较大框宽的这个倍数（扇形里相邻牌间距远小于它，远处离群框远大于它）。 */
    const val ADJACENT_WIDTH_RATIO = 3.0f

    /** 合并结果不合规时，退回的单帧结果至少要出现的帧数。 */
    const val FALLBACK_MIN_FRAMES = 2

    fun resolve(frameDetections: List<List<YoloDetection>>): PlayVoteOutcome? {
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

        val slots = mergeSlots(nonEmpty)
        val kept = if (nonEmpty.size >= OUTLIER_FILTER_MIN_FRAMES) dropOutliers(slots) else slots
        val mergedSlots = kept.map { it.detection }
        val mergedCards = countsOf(mergedSlots)
        if (GoujiPlayRules.check(mergedCards) is PlayRuleCheck.Suspicious) {
            fallbackFrame(nonEmpty, frameCounts)?.let { frame ->
                return PlayVoteOutcome(
                    cards = countsOf(frame),
                    slotDetections = frame,
                    framesUsed = nonEmpty.size,
                    mergedFromDisagreement = false,
                    fellBackToFrame = true
                )
            }
        }
        return PlayVoteOutcome(
            cards = mergedCards,
            slotDetections = mergedSlots,
            framesUsed = nonEmpty.size,
            mergedFromDisagreement = true
        )
    }

    /** 把多帧检测框按位置聚成牌位（不过滤）：每帧内按置信度从高到低认领牌位，同帧内同一牌位不重复认领，跨帧取置信度最高者。 */
    fun mergeDetectionSlots(frames: List<List<YoloDetection>>): List<YoloDetection> =
        mergeSlots(frames).map { it.detection }

    fun countsOf(detections: List<YoloDetection>): RankCounts {
        if (detections.isEmpty()) return RankCounts.zero()
        val counts = HashMap<Rank, Int>()
        detections.forEach { counts[it.rank] = (counts[it.rank] ?: 0) + 1 }
        return RankCounts.of(counts)
    }

    /** 两个检测框是同一牌位：中心差按两框中较小的宽/高折算，不用固定像素。 */
    internal fun sameSlot(a: YoloDetection, b: YoloDetection): Boolean =
        abs(a.centerX - b.centerX) <= CENTER_TOLERANCE_RATIO * minOf(a.width, b.width) &&
            abs(a.centerY - b.centerY) <= CENTER_TOLERANCE_RATIO * minOf(a.height, b.height)

    /** 横向相邻：同一排（纵向差不超过较小高）且中心距在若干个框宽之内。 */
    private fun adjacent(a: YoloDetection, b: YoloDetection): Boolean =
        abs(a.centerY - b.centerY) <= minOf(a.height, b.height) &&
            abs(a.centerX - b.centerX) <= ADJACENT_WIDTH_RATIO * maxOf(a.width, b.width)

    private class Slot(var detection: YoloDetection) {
        /** 这个牌位在几个不同的帧里出现过（同帧最多认领一次）。 */
        var frames = 1
    }

    private fun mergeSlots(frames: List<List<YoloDetection>>): List<Slot> {
        val slots = ArrayList<Slot>()
        for (detections in frames) {
            val claimed = HashSet<Slot>()
            for (detection in detections.sortedByDescending { it.confidence }) {
                val slot = slots
                    .filter { it !in claimed && sameSlot(it.detection, detection) }
                    .minByOrNull { abs(it.detection.centerX - detection.centerX) }
                if (slot == null) {
                    claimed += Slot(detection).also { slots += it }
                } else {
                    claimed += slot
                    slot.frames += 1
                    if (detection.confidence > slot.detection.confidence) {
                        slot.detection = detection
                    }
                }
            }
        }
        return slots
    }

    /**
     * 只在 1 帧出现的牌位：点数必须是多帧牌位的主点数或配牌，且与已保留牌位横向相邻（可以链式相邻）。
     * 没有任何多帧牌位（各帧框互不重合）时没有可依据的主点数，不过滤，交给后面的规则校验。
     */
    private fun dropOutliers(slots: List<Slot>): List<Slot> {
        val multi = slots.filter { it.frames >= 2 }
        if (multi.isEmpty()) return slots
        val allowedMain = multi.map { it.detection.rank }.filterTo(HashSet()) { it !in GoujiPlayRules.WILD_RANKS }
        val kept = HashSet<Slot>(multi)
        val pending = slots.filter { it.frames < 2 }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val slot = iterator.next()
                val rank = slot.detection.rank
                val rankOk = rank in allowedMain || rank in GoujiPlayRules.WILD_RANKS
                if (rankOk && kept.any { adjacent(it.detection, slot.detection) }) {
                    kept += slot
                    iterator.remove()
                    changed = true
                }
            }
        }
        return slots.filter { it in kept }
    }

    /** 出现 >= [FALLBACK_MIN_FRAMES] 帧且能通过规则的那一帧结果：出现次数多的优先，次数相同取更晚的。 */
    private fun fallbackFrame(
        frames: List<List<YoloDetection>>,
        frameCounts: List<RankCounts>
    ): List<YoloDetection>? {
        val candidate = frameCounts
            .withIndex()
            .groupBy({ it.value }, { it.index })
            .filter { (counts, indexes) ->
                indexes.size >= FALLBACK_MIN_FRAMES && GoujiPlayRules.check(counts) is PlayRuleCheck.Valid
            }
            .entries
            .maxWithOrNull(compareBy({ it.value.size }, { it.value.last() }))
            ?: return null
        return frames[candidate.value.last()]
    }
}
