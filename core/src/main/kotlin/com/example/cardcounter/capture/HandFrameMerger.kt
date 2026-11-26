package com.example.cardcounter.capture

import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.vision.YoloDetection
import kotlin.math.abs

/**
 * 一帧手牌检测合并后的结果。
 * [single] 是这一帧自己认出来的张数，[merged] 是停稳期间所有帧合并后的张数（超库存时退回单帧，等于 [single]）。
 */
data class HandMergeResult(
    val single: RankCounts,
    val merged: RankCounts,
    /** 这一帧的检测框。 */
    val frameDetections: List<YoloDetection>,
    /** 合并后每个牌位留下的检测框（每个牌位取把握度最高的那次）；退回单帧时等于 [frameDetections]。 */
    val slotDetections: List<YoloDetection>,
    /** 参与合并的帧数（含这一帧）。 */
    val frames: Int,
    /** 合并结果超过某个点数的库存，已退回这一帧的单帧结果并重新从这一帧开始累积。 */
    val fellBack: Boolean
) {
    /** 合并结果比这一帧自己多出牌：说明有的牌时有时无。 */
    val gainedCards: Boolean get() = merged.total() > single.total()

    /** 为什么这一帧值得存成训练样本；空 = 不用存。 */
    fun sampleTriggers(): List<String> = buildList {
        if (gainedCards) add(HandSampleTrigger.MERGE_GAIN)
        if (frameDetections.any { it.confidence < HandSampleTrigger.LOW_CONFIDENCE_LIMIT }) {
            add(HandSampleTrigger.LOW_CONFIDENCE)
        }
    }
}

object HandSampleTrigger {
    const val MERGE_GAIN = "MERGE_GAIN"
    const val LOW_CONFIDENCE = "LOW_CONFIDENCE"
    const val LOW_CONFIDENCE_LIMIT = 0.5f
}

/**
 * 手牌画面停稳期间的多帧合并：手机每秒重认一次，把握度低的牌（例如 0.3）时有时无，
 * 只取最新一帧会漏牌。这里把每一帧的检测框按牌位累积，某个牌位只要有一帧认出来就算一张。
 *
 * 牌位 = 排（centerY 相差不超过 [rowTolerance]）+ 横向位置（centerX 相差不超过 [xTolerance]，约半张牌间距）。
 * 同一牌位不同帧认成不同点数时，取把握度最高的那次。坐标是 320 输入画布像素。
 *
 * 画面一变（[add] 传入的 scene 编号变了）就清空从头再来；调用方保证画面在动的帧不传进来。
 * 非线程安全，由调用方串行使用。
 */
class HandFrameMerger(
    private val inventory: RankCounts = Rank.initialInventory,
    private val xTolerance: Float = X_TOLERANCE,
    private val rowTolerance: Float = ROW_TOLERANCE
) {
    private class Slot(var detection: YoloDetection)

    private val slots = ArrayList<Slot>()
    private var scene: Long? = null
    private var frames = 0

    fun reset() {
        slots.clear()
        scene = null
        frames = 0
    }

    /** 并入一帧。[scene] 和上一帧不同 = 画面变过，先清空累积再并入。 */
    fun add(scene: Long, detections: List<YoloDetection>): HandMergeResult {
        if (this.scene != scene) {
            reset()
            this.scene = scene
        }
        absorb(detections)
        frames += 1
        val single = countsOf(detections)
        val merged = countsOf(slots.map { it.detection })
        if (merged.fitsWithin(inventory)) {
            return HandMergeResult(single, merged, detections, slots.map { it.detection }, frames, fellBack = false)
        }
        // 超库存说明牌位对错了（抖动把同一张牌分成两个牌位）：这一帧退回单帧结果，累积从这一帧重新开始。
        slots.clear()
        frames = 1
        absorb(detections)
        return HandMergeResult(single, single, detections, detections, frames, fellBack = true)
    }

    private fun absorb(detections: List<YoloDetection>) {
        // 把握度高的先认领牌位；同一帧里一个牌位只能认领一次，避免两张相邻的牌并成一张。
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
                if (detection.confidence > slot.detection.confidence) slot.detection = detection
            }
        }
    }

    private fun countsOf(detections: List<YoloDetection>): RankCounts {
        val counts = HashMap<Rank, Int>()
        detections.forEach { counts[it.rank] = (counts[it.rank] ?: 0) + 1 }
        return RankCounts.of(counts)
    }

    companion object {
        /** 同排相邻两张牌中心相距约 16（320 画布像素）；取不到一半多一点，抖动吃得住、相邻牌不会并。 */
        const val X_TOLERANCE = 7f

        /** 两排中心相距约 22；排内检测框中心上下抖动 1~2。 */
        const val ROW_TOLERANCE = 8f
    }
}

/**
 * 低把握手牌样本的限流：同一手牌画面（scene 编号不变）同一原因只存一次；每一局最多存 [maxPerGame] 份，
 * 免得画面反复抖动把 50 组的证据目录占满、挤掉别的证据。非线程安全，由调用方串行使用。
 */
class HandSampleThrottle(private val maxPerGame: Int = MAX_PER_GAME) {
    private var scene: Long? = null
    private val savedReasons = HashSet<String>()
    private var saved = 0

    /** 新局或手牌确认后：重新计数。 */
    fun reset() {
        scene = null
        savedReasons.clear()
        saved = 0
    }

    /** 这一帧要不要存：有这个画面还没存过的原因才存；返回 true 就算存过了。 */
    fun admit(scene: Long, reasons: List<String>): Boolean {
        if (this.scene != scene) {
            this.scene = scene
            savedReasons.clear()
        }
        if (saved >= maxPerGame) return false
        val fresh = reasons.filter { savedReasons.add(it) }
        if (fresh.isEmpty()) return false
        saved += 1
        return true
    }

    companion object {
        const val MAX_PER_GAME = 4
    }
}
