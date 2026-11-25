package com.example.cardcounter.capture

import kotlin.math.abs

/**
 * 出牌区的轻量指纹：在这一区的裁剪范围内按固定网格取点（0xRRGGBB）。
 * 帧交出、位图被回收之后，缓存还要拿上一帧继续判断“画面变没变”，所以只比较指纹，不碰位图。
 */
object RoiFingerprint {
    const val COLUMNS = 40
    const val ROWS = 16
    private const val CHANNEL_DELTA = 12
    private const val CHANGED_RATIO = 0.03f

    /** [pixel] 返回 1280x578 画布坐标 (x, y) 处的 0xRRGGBB。 */
    fun of(zone: PlayZone, pixel: (x: Int, y: Int) -> Int): IntArray {
        val crop = zone.yoloCrop(1_280, 578)
        return IntArray(COLUMNS * ROWS) { index ->
            val x = crop.left + (crop.width - 1) * (index % COLUMNS) / (COLUMNS - 1)
            val y = crop.top + (crop.height - 1) * (index / COLUMNS) / (ROWS - 1)
            pixel(x, y) and 0xffffff
        }
    }

    /** 与上一次指纹相比，变化的点不到 3% 就算静止；两次指纹大小不同不算静止。 */
    fun isStill(previous: IntArray, current: IntArray): Boolean {
        if (previous.size != current.size || current.isEmpty()) return false
        var changed = 0
        for (index in current.indices) {
            val before = previous[index]
            val after = current[index]
            if (channelChanged(before, after, 16) || channelChanged(before, after, 8) || channelChanged(before, after, 0)) {
                changed++
            }
        }
        return changed.toFloat() / current.size < CHANGED_RATIO
    }

    private fun channelChanged(before: Int, after: Int, shift: Int): Boolean =
        abs(((before shr shift) and 0xff) - ((after shr shift) and 0xff)) > CHANNEL_DELTA
}
