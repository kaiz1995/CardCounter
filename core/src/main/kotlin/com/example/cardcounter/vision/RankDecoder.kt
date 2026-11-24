package com.example.cardcounter.vision

import com.example.cardcounter.domain.Rank

/** 新模型（15 点数）的输入输出契约，见 docs/rebuild/DATA-FORMATS.md 第 4 节。 */
object RankModelContract {
    const val INPUT_SIZE = 320

    /** 类别号 = Rank.entries 的顺序（0 BIG_JOKER … 14 THREE）。 */
    const val CLASS_COUNT = 15
    const val CANDIDATES = 2100

    /** 前 4 行是 cx、cy、w、h（320 输入画布像素），后 15 行是各点数的分数。 */
    const val OUTPUT_ROWS = 4 + CLASS_COUNT
    const val OUTPUT_SIZE = OUTPUT_ROWS * CANDIDATES

    const val CONFIDENCE_THRESHOLD = 0.25f
    const val NMS_IOU_THRESHOLD = 0.45f
}

/**
 * 解码模型原始输出 output0（[1,19,2100] 展平成 19×2100，行优先），与电脑端
 * tools/training/eval_crops.py 的 decode_new 逐步一致：
 * 每个候选取 15 个分数的最大值和类别（并列取编号小的类），最大值 >= 阈值才留下，
 * 框转成 x1y1x2y2 后不分类别做 NMS（IoU 超过阈值的被压掉），按分数从高到低保留。
 *
 * 分数相同时按候选编号从小到大排，保证同样的输入永远得到同样的结果。
 * 返回的 centerX/centerY/width/height 仍是 320 输入画布像素，没有换算成 0..1。
 */
fun decodeRankOutput(
    output: FloatArray,
    confidenceThreshold: Float = RankModelContract.CONFIDENCE_THRESHOLD,
    nmsIouThreshold: Float = RankModelContract.NMS_IOU_THRESHOLD
): List<YoloDetection> {
    require(output.size == RankModelContract.OUTPUT_SIZE) {
        "模型输出长度应为 ${RankModelContract.OUTPUT_SIZE}，实际 ${output.size}"
    }
    val count = RankModelContract.CANDIDATES
    val candidates = ArrayList<Candidate>()
    for (index in 0 until count) {
        var best = output[4 * count + index]
        var classId = 0
        for (candidateClass in 1 until RankModelContract.CLASS_COUNT) {
            val score = output[(4 + candidateClass) * count + index]
            if (score > best) {
                best = score
                classId = candidateClass
            }
        }
        if (!(best >= confidenceThreshold)) continue
        val cx = output[index]
        val cy = output[count + index]
        val width = output[2 * count + index]
        val height = output[3 * count + index]
        candidates += Candidate(
            index = index,
            classId = classId,
            score = best,
            cx = cx,
            cy = cy,
            width = width,
            height = height,
            x1 = cx - width / 2f,
            y1 = cy - height / 2f,
            x2 = cx + width / 2f,
            y2 = cy + height / 2f
        )
    }

    val ordered = candidates.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.index })
    val suppressed = BooleanArray(ordered.size)
    val kept = ArrayList<YoloDetection>()
    for (first in ordered.indices) {
        if (suppressed[first]) continue
        val winner = ordered[first]
        val rank = Rank.entries[winner.classId]
        kept += YoloDetection(
            rank = rank,
            rawLabel = rank.name,
            confidence = winner.score,
            centerX = winner.cx,
            centerY = winner.cy,
            width = winner.width,
            height = winner.height
        )
        for (second in first + 1 until ordered.size) {
            if (!suppressed[second] && iou(winner, ordered[second]) > nmsIouThreshold) {
                suppressed[second] = true
            }
        }
    }
    return kept
}

private class Candidate(
    val index: Int,
    val classId: Int,
    val score: Float,
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
)

private fun iou(first: Candidate, second: Candidate): Float {
    val overlapWidth = maxOf(minOf(first.x2, second.x2) - maxOf(first.x1, second.x1), 0f)
    val overlapHeight = maxOf(minOf(first.y2, second.y2) - maxOf(first.y1, second.y1), 0f)
    val intersection = overlapWidth * overlapHeight
    val firstArea = (first.x2 - first.x1) * (first.y2 - first.y1)
    val secondArea = (second.x2 - second.x1) * (second.y2 - second.y1)
    return intersection / maxOf(firstArea + secondArea - intersection, 1e-6f)
}
