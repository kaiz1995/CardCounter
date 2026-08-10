package com.example.cardcounter.capture

import android.media.Image
import kotlin.math.abs
import kotlin.math.roundToInt

/** 固定游戏皮肤的手牌左边界计数；独立于 Rank 模板。 */
class HandCardPositionDetector {
    fun snapshot(image: Image): IntArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val result = IntArray(WIDTH * HEIGHT)
        for (y in TOP_ROW + BOTTOM_ROW) for (x in MIN_X - 1..MAX_X) {
            val sourceX = x * image.width / WIDTH
            val sourceY = y * image.height / HEIGHT
            val offset = sourceY * plane.rowStride + sourceX * plane.pixelStride
            val red = buffer.get(offset).toInt() and 0xff
            val green = buffer.get(offset + 1).toInt() and 0xff
            val blue = buffer.get(offset + 2).toInt() and 0xff
            result[y * WIDTH + x] = (red * 30 + green * 59 + blue * 11) / 100
        }
        return result
    }

    fun detect(luma: IntArray): Int? {
        require(luma.size == WIDTH * HEIGHT)
        val (top, bottom) = rowCounts(luma)
        return if (top == null || bottom == null) null else top + bottom
    }

    internal fun rowCounts(luma: IntArray): Pair<Int?, Int?> {
        require(luma.size == WIDTH * HEIGHT)
        return rowCount(luma, TOP_ROW) to rowCount(luma, BOTTOM_ROW)
    }

    private fun rowCount(luma: IntArray, row: IntRange): Int? {
        val selected = edgePositions(luma, row)
        if (selected.size < 2) return null
        val left = selected.min()
        val right = selected.max()
        // ponytail: 固定皮肤几何；换皮肤或分辨率变化时改为自动标定。
        return ((right - CARD_WIDTH - left).toDouble() / CARD_PITCH).roundToInt() + 1
    }

    private fun edgePositions(luma: IntArray, row: IntRange): List<Int> {
        val scores = DoubleArray(MAX_X + 1)
        for (x in MIN_X..MAX_X) {
            var total = 0
            for (y in row) total += abs(luma[y * WIDTH + x] - luma[y * WIDTH + x - 1])
            scores[x] = total.toDouble() / row.count()
        }
        val peaks = mutableListOf<Pair<Int, Double>>()
        for (x in MIN_X..MAX_X) {
            if (scores[x] < EDGE_THRESHOLD || (x - NEIGHBORHOOD..x + NEIGHBORHOOD)
                    .any { it in MIN_X..MAX_X && scores[it] > scores[x] }
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
        private const val MIN_X = 225
        private const val MAX_X = 1_059
        private const val EDGE_THRESHOLD = 20.0
        private const val NEIGHBORHOOD = 6
        private const val MIN_EDGE_GAP = 28
        private const val CARD_WIDTH = 90
        private const val CARD_PITCH = 45
        private val TOP_ROW = 420..450
        private val BOTTOM_ROW = 490..540
    }
}
