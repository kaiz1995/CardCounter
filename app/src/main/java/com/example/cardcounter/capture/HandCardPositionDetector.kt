package com.example.cardcounter.capture

import android.media.Image
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 固定游戏皮肤的手牌边缘计数；独立于 Rank 模板。
 * 上下两排牌大小、错开间距不同，各用一套标定（按 9 张目视数过张数的截图量出）。
 * 采样行见 [HandRegions.topEdgeRow] / [HandRegions.bottomEdgeRow]，悬浮窗上的框不许盖住它们。
 */
class HandCardPositionDetector {
    /** 一排牌的几何：牌宽、相邻两张的错开间距、采样行。 */
    private class RowGeometry(val area: HandRegions.Area, val cardWidth: Int, val cardPitch: Double) {
        val rows: IntRange get() = area.top until area.bottom
    }

    fun snapshot(image: Image): IntArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val result = IntArray(WIDTH * HEIGHT)
        for (geometry in listOf(TOP, BOTTOM)) {
            for (y in geometry.rows) for (x in geometry.area.left..geometry.area.right - 1) {
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
     * 差值不在 {0,1} 说明有一排数错了或正在发牌，当作没数出来（null）。
     */
    fun detect(rows: Pair<Int?, Int?>): Int? {
        val (top, bottom) = rows
        if (top == null || bottom == null) return null
        return if (bottom - top in 0..1) top + bottom else null
    }

    /** 上、下两排各自数出来的张数；某一排没找到足够的边缘为 null。 */
    internal fun rowCounts(luma: IntArray): Pair<Int?, Int?> {
        require(luma.size == WIDTH * HEIGHT)
        val top = edgePositions(luma, TOP.rows)
        val bottom = edgePositions(luma, BOTTOM.rows)
        // 下排比上排短时，上排两头的牌会从下排两侧伸出来，下排采样行读到的是上排的外沿，数不出下排。
        val bottomReadsTopOutline = top.size >= 2 && bottom.size >= 2 &&
            abs(bottom.min() - top.min()) <= OUTLINE_TOLERANCE &&
            abs(bottom.max() - top.max()) <= OUTLINE_TOLERANCE
        return rowCount(top, TOP) to if (bottomReadsTopOutline) null else rowCount(bottom, BOTTOM)
    }

    private fun rowCount(selected: List<Int>, geometry: RowGeometry): Int? {
        if (selected.size < 2) return null
        val left = selected.min()
        val right = selected.max()
        // ponytail: 固定皮肤几何；换皮肤或分辨率变化时改为自动标定。
        return ((right - geometry.cardWidth - left) / geometry.cardPitch).roundToInt() + 1
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

        /** 下排两端和上排两端相差不超过它就当下排读到的是上排外沿；已核对样本里正常帧的最小差是 3px。 */
        private const val OUTLINE_TOLERANCE = 2
        private val TOP = RowGeometry(HandRegions.topEdgeRow, cardWidth = 90, cardPitch = 44.7)
        private val BOTTOM = RowGeometry(HandRegions.bottomEdgeRow, cardWidth = 90, cardPitch = 46.4)
    }
}
