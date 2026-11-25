package com.example.cardcounter.capture

import android.media.Image
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 固定游戏皮肤的手牌边缘计数；独立于 Rank 模板。
 * 上下两排牌大小、错开间距不同，各用一套标定（按 9 张目视数过张数的截图量出）。
 * 采样行见 [HandRegions.topEdgeRow] / [HandRegions.bottomEdgeRow] / [HandRegions.bottomBorderRows]，
 * 悬浮窗上的框不许盖住它们（OverlayLayoutTest.noBoxOnTheOverlayCoversTheHandCountSamplingRows）。
 */
class HandCardPositionDetector {
    /** 一排牌的几何：牌宽、相邻两张的平均错开间距、采样行。 */
    private class RowGeometry(val area: HandRegions.Area, val cardWidth: Int, val cardPitch: Double) {
        val rows: IntRange get() = area.top until area.bottom
    }

    fun snapshot(image: Image): IntArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val result = IntArray(WIDTH * HEIGHT)
        for (area in listOf(TOP.area, BOTTOM.area, HandRegions.bottomBorderRows)) {
            for (y in area.top until area.bottom) for (x in area.left until area.right) {
                val sourceX = x * image.width / WIDTH
                val sourceY = y * image.height / HEIGHT
                val offset = sourceY * plane.rowStride + sourceX * plane.pixelStride
                val red = buffer.get(offset).toInt() and 0xff
                val green = buffer.get(offset + 1).toInt() and 0xff
                val blue = buffer.get(offset + 2).toInt() and 0xff
                result[y * WIDTH + x] = (red * 30 + green * 59 + blue * 11) / 100
            }
        }
        return result
    }

    fun detect(luma: IntArray): Int? = detect(rowCounts(luma))

    /**
     * 两排合计张数。游戏是下排先排满，已知样本里 下排−上排 只会是 0 或 1；
     * 差值不在 {0,1}、或某排没数出来，说明有一排数错了或正在发牌，当作没数出来（null）。
     */
    fun detect(rows: Pair<Int?, Int?>): Int? {
        val (top, bottom) = rows
        if (top == null || bottom == null || top <= 0 || bottom <= 0) return null
        return if (bottom - top in 0..1) top + bottom else null
    }

    /** 上、下两排各自数出来的张数；某一排没数出来为 null（不会是 0）。 */
    internal fun rowCounts(luma: IntArray): Pair<Int?, Int?> {
        require(luma.size == WIDTH * HEIGHT)
        val top = edgePositions(luma, TOP.rows)
        val bottom = edgePositions(luma, BOTTOM.rows)
        // 下排比上排短时，上排两头的牌会从下排两侧伸出来，下排采样行读到的是上排的外沿：
        // 那里没有下排牌自己的白色顶边，数不出下排。两排正好同宽时两端也有白边，照常数。
        val bottomCount = if (hasOwnTopBorder(luma, bottom)) rowCount(bottom, BOTTOM) else null
        return countByGaps(top, TOP) to bottomCount
    }

    /**
     * 上排逐段数：相邻两条牌边的距离各自按平均错开间距取整，再加起来。
     * 同点数相邻约 42px、不同点数约 50px；整排跨度一次除间距时，这些出入会一张张累积（16 张算成 16.7 张，数成 17），
     * 逐段取整每段的出入都不到半张，不会累积。漏掉一条牌边时那一段约 78~92px，取整成 2 张，照样数对。
     * 最右一条是最后一张牌的右边，先换算成它的左边。
     * 下排不用这个办法：下排采样行常有离上一条只有 30px 左右的假牌边，逐段数会多数，下排仍按 [rowCount] 数。
     */
    private fun countByGaps(selected: List<Int>, geometry: RowGeometry): Int? {
        if (selected.size < 2) return null
        val sorted = selected.sorted()
        if (sorted.last() - sorted.first() < geometry.cardWidth) return null
        val lefts = (sorted.dropLast(1) + (sorted.last() - geometry.cardWidth)).sorted()
        val count = 1 + lefts.zipWithNext { left, next -> ((next - left) / geometry.cardPitch).roundToInt() }.sum()
        return count.takeIf { it > 0 }
    }

    /**
     * 下排按整排跨度除平均错开间距数张数。同点数相邻约 42px、不同点数约 50px，跨度天生有将近半张的出入，
     * 算出来离取整分界不到 [AMBIGUOUS_MARGIN] 张时，在两个候选里取和找到的牌边条数更接近的那个。
     */
    private fun rowCount(selected: List<Int>, geometry: RowGeometry): Int? {
        if (selected.size < 2) return null
        val left = selected.min()
        val right = selected.max()
        if (right - left < geometry.cardWidth) return null
        // ponytail: 固定皮肤几何；换皮肤或分辨率变化时改为自动标定。
        val raw = (right - geometry.cardWidth - left) / geometry.cardPitch + 1
        val nearest = raw.roundToInt()
        val count = if (abs(raw - nearest) > 0.5 - AMBIGUOUS_MARGIN) {
            val lower = floor(raw).toInt()
            val byEdges = selected.size - 1
            if (abs(lower - byEdges) < abs(lower + 1 - byEdges)) lower else lower + 1
        } else nearest
        return count.takeIf { it > 0 }
    }

    /** 下排两端的牌面都要在 y≈452 往下有一道由暗变亮的白色顶边；上排外沿在那里是变暗的。 */
    private fun hasOwnTopBorder(luma: IntArray, bottom: List<Int>): Boolean {
        if (bottom.size < 2) return false
        val left = bottom.min()
        val right = bottom.max()
        return borderStep(luma, left + BORDER_INSET_NEAR, left + BORDER_INSET_FAR) >= BORDER_STEP &&
            borderStep(luma, right - BORDER_INSET_FAR, right - BORDER_INSET_NEAR) >= BORDER_STEP
    }

    private fun borderStep(luma: IntArray, fromX: Int, untilX: Int): Double {
        val area = HandRegions.bottomBorderRows
        val x0 = fromX.coerceIn(area.left, area.right)
        val x1 = untilX.coerceIn(x0, area.right)
        if (x1 <= x0) return 0.0
        fun rowMean(y: Int): Double = (x0 until x1).sumOf { luma[y * WIDTH + it] }.toDouble() / (x1 - x0)
        val above = rowMean(area.top)
        val brightest = (area.top + 2 until area.bottom).maxOf(::rowMean)
        return brightest - above
    }

    private fun edgePositions(luma: IntArray, rows: IntRange): List<Int> {
        val scores = DoubleArray(HandRegions.EDGE_MAX_X + 1)
        for (x in HandRegions.EDGE_MIN_X..HandRegions.EDGE_MAX_X) {
            var total = 0
            for (y in rows) total += abs(luma[y * WIDTH + x] - luma[y * WIDTH + x - 1])
            scores[x] = total.toDouble() / rows.count()
        }
        val peaks = mutableListOf<Pair<Int, Double>>()
        for (x in HandRegions.EDGE_MIN_X..HandRegions.EDGE_MAX_X) {
            if (scores[x] < EDGE_THRESHOLD || (x - NEIGHBORHOOD..x + NEIGHBORHOOD)
                    .any { it in HandRegions.EDGE_MIN_X..HandRegions.EDGE_MAX_X && scores[it] > scores[x] }
            ) continue
            peaks += x to scores[x]
        }
        val selected = mutableListOf<Pair<Int, Double>>()
        peaks.sortedByDescending { it.second }.forEach { candidate ->
            if (selected.none { abs(it.first - candidate.first) <= MIN_EDGE_GAP }) selected += candidate
        }
        return selected.map { it.first }
    }

    companion object {
        private const val WIDTH = 1_280
        private const val HEIGHT = 578
        private const val EDGE_THRESHOLD = 40.0
        private const val NEIGHBORHOOD = 6
        private const val MIN_EDGE_GAP = 28

        /** 跨度算出的张数离取整分界不到它时改看牌边条数；已核对样本里最近的是 145333 下排 16.517。 */
        private const val AMBIGUOUS_MARGIN = 0.15

        /** 白色顶边要比上方亮出的最小幅度；已核对样本里真下排最小 +55，上排外沿都是变暗。 */
        private const val BORDER_STEP = 15.0
        private const val BORDER_INSET_NEAR = 2
        private const val BORDER_INSET_FAR = 12

        private val TOP = RowGeometry(HandRegions.topEdgeRow, cardWidth = 90, cardPitch = 44.7)
        private val BOTTOM = RowGeometry(HandRegions.bottomEdgeRow, cardWidth = 90, cardPitch = 46.4)
    }
}
