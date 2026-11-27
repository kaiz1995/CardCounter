package com.example.cardcounter.vision

import com.example.cardcounter.domain.Rank

/** 新模型（15 点数）的输入输出契约，见 docs/rebuild/DATA-FORMATS.md 第 4 节。 */
object RankModelContract {
    const val INPUT_SIZE = 320

    /** 当前打包模型（app/src/main/assets/poker_rank15_yolo11n_320.onnx）的 SHA-256。 */
    const val MODEL_SHA256 = "f330379a5468ae300276c693da7e3146c516276ccd8a2627ea20ce7b7de2d5f6"

    /** 视觉后处理规则与模型版本标识（由 VisionRuleVersionGuardTest 机械校验代码与 ONNX 模型 SHA-256 一致）。 */
    const val VISION_RULE_VERSION = "v4-037e56d4b60d"

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

private const val NARROW_JOKER_MIN_ASPECT = 1.85f
private const val NARROW_JOKER_MAX_WIDTH_RATIO = 0.72f
private const val NARROW_JOKER_TOP15_PERCENT = 15
private const val NARROW_JOKER_TOP25_PERCENT = 25
private const val NARROW_JOKER_TOP_PERCENT = 65
private const val NARROW_JOKER_MIN_TOP25_RIGHT_INK = 4
private const val NARROW_JOKER_MAX_TOP25_LEFT_RATIO_NUM = 1
private const val NARROW_JOKER_MAX_TOP25_LEFT_RATIO_DEN = 3
private const val NARROW_JOKER_MIN_INK_PIXELS = 8
private const val NARROW_JOKER_MAX_BOTTOM_RED_FOR_SMALL = 2

/**
 * 修正扇形最左侧被圆标/印章部分遮挡、被模型误判为普通牌的王牌竖条（与 tools/dataset/phone_vision.py 同口径）：
 * 1. 几何：多张牌的最左框、高宽比 >= 1.85、宽度 <= 0.72 × 其他牌最大宽；
 * 2. 字形墨迹形态（独立强证据，防止“出牌”圆标压住普通牌左下角形成窄条误判）：
 *    王牌竖条纵向排“JOKER”，顶部 0..25% 必为无左顶横的细竖笔“J”，集中在框右半侧；
 *    普通牌（10/8/K/Q/J等）顶部数字形横跨框宽或左半有大量笔画：
 *    - 顶部 15% 内逐行墨迹（红或低饱和暗像素 <160）最大横向跨度 <= 框宽 50%；
 *    - 顶部 25% 右半区墨迹 >= 4 点，且左半区墨迹 <= 右半区 1/3；
 * 3. 红/黑归属与小王防圆标侵入：上半部 65% 红/黑墨迹 >= 8 分别判大王/小王，且判小王时底边 35% 红像素 <= 2（防红圆标压入普通黑牌底部）。
 */
fun refineNarrowLeftJoker(
    detections: List<YoloDetection>,
    inputArgb320: IntArray
): List<YoloDetection> {
    val size = RankModelContract.INPUT_SIZE
    if (detections.size < 2 || inputArgb320.size != size * size) return detections
    var leftIndex = 0
    for (i in 1 until detections.size) {
        if (detections[i].centerX < detections[leftIndex].centerX) leftIndex = i
    }
    val leftmost = detections[leftIndex]
    if (leftmost.rank == Rank.BIG_JOKER || leftmost.rank == Rank.SMALL_JOKER) return detections
    if (leftmost.width <= 0f || leftmost.height / leftmost.width < NARROW_JOKER_MIN_ASPECT) return detections
    var maxOtherWidth = 0f
    for (i in detections.indices) {
        if (i != leftIndex && detections[i].width > maxOtherWidth) maxOtherWidth = detections[i].width
    }
    if (leftmost.width > NARROW_JOKER_MAX_WIDTH_RATIO * maxOtherWidth) return detections

    val x1 = kotlin.math.floor(leftmost.centerX - leftmost.width / 2f).toInt().coerceIn(0, size - 1)
    val x2 = kotlin.math.ceil(leftmost.centerX + leftmost.width / 2f).toInt().coerceIn(x1 + 1, size)
    val y1 = kotlin.math.floor(leftmost.centerY - leftmost.height / 2f).toInt().coerceIn(0, size - 1)
    val y2 = kotlin.math.ceil(leftmost.centerY + leftmost.height / 2f).toInt().coerceIn(y1 + 1, size)
    val boxWidth = x2 - x1
    val boxHeight = y2 - y1
    val yTop15 = (y1 + maxOf(1, boxHeight * NARROW_JOKER_TOP15_PERCENT / 100)).coerceAtMost(size)
    val yTop25 = (y1 + maxOf(1, boxHeight * NARROW_JOKER_TOP25_PERCENT / 100)).coerceAtMost(size)
    val yTopEnd = (y1 + maxOf(1, boxHeight * NARROW_JOKER_TOP_PERCENT / 100)).coerceAtMost(size)
    val xMid = x1 + boxWidth / 2

    var maxTop15Span = 0
    var top25Left = 0
    var top25Right = 0
    var redInk = 0
    var blackInk = 0
    var bottomRedInk = 0

    for (y in y1 until y2) {
        val rowOffset = y * size
        var rowMinX = -1
        var rowMaxX = -1
        for (x in x1 until x2) {
            val rgb = inputArgb320[rowOffset + x]
            val r = (rgb shr 16) and 0xff
            val g = (rgb shr 8) and 0xff
            val b = rgb and 0xff
            val isRed = r >= 120 && r - g >= 45 && r - b >= 45
            val isBlack = r < 115 && g < 115 && b < 115
            val high = maxOf(r, g, b)
            val low = minOf(r, g, b)
            val isMorphInk = isRed || (high < 160 && high - low <= 45)
            if (isMorphInk) {
                if (rowMinX < 0) rowMinX = x
                rowMaxX = x
                if (y < yTop25) {
                    if (x < xMid) top25Left++ else top25Right++
                }
            }
            if (y < yTopEnd) {
                if (isRed) {
                    redInk++
                } else if (isBlack) {
                    blackInk++
                }
            } else if (isRed) {
                bottomRedInk++
            }
        }
        if (y < yTop15 && rowMinX >= 0) {
            maxTop15Span = maxOf(maxTop15Span, rowMaxX - rowMinX + 1)
        }
    }

    if (maxTop15Span == 0 || maxTop15Span * 2 > boxWidth) return detections
    if (top25Right < NARROW_JOKER_MIN_TOP25_RIGHT_INK ||
        top25Left * NARROW_JOKER_MAX_TOP25_LEFT_RATIO_DEN > top25Right * NARROW_JOKER_MAX_TOP25_LEFT_RATIO_NUM
    ) {
        return detections
    }

    val refinedRank = when {
        redInk >= NARROW_JOKER_MIN_INK_PIXELS && redInk >= blackInk * 2 -> Rank.BIG_JOKER
        blackInk >= NARROW_JOKER_MIN_INK_PIXELS &&
            (redInk <= 2 || blackInk >= redInk * 2) &&
            bottomRedInk <= NARROW_JOKER_MAX_BOTTOM_RED_FOR_SMALL -> Rank.SMALL_JOKER
        else -> return detections
    }
    return detections.mapIndexed { index, det ->
        if (index == leftIndex) det.copy(rank = refinedRank, rawLabel = refinedRank.name) else det
    }
}
