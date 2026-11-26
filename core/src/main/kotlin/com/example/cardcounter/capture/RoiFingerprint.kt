package com.example.cardcounter.capture

import kotlin.math.abs

/**
 * 出牌区的轻量指纹：在这一区的裁剪范围内按固定网格取点（0xRRGGBB）。
 * 帧交出、位图被回收之后，缓存还要拿上一帧继续判断“画面变没变”，所以只比较指纹，不碰位图。
 */
object RoiFingerprint {
    const val COLUMNS = 40
    const val ROWS = 16
    /**
     * 单个 RGB 通道变动容限。
     * 依据 dev 实测：5~7 张大牌与“少于10张”光效渐退在相邻静止帧底部产生 13..60 的通道微扰；
     * 全部 189 次真实 REPLACE_DIRECT 换牌在阈值 60 下仍有 >= 0.039 (> 0.03) 变化，提至 60 可吸收光效而不漏换牌。
     */
    const val CHANNEL_DELTA = 60
    private const val CHANGED_RATIO = 0.03f
    private const val CORE_ROW_START = 4
    private const val CORE_ROW_END_EXCLUSIVE = 12
    /** 默认核心区变化阈值（row 4..11，320 点）；STABLE 与 COVERED 由 StablePlayGate 传入各自门限。 */
    const val DEFAULT_CORE_CHANGE_MIN_POINTS = 4

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
            if (pixelChanged(previous[index], current[index])) changed++
        }
        return changed.toFloat() / current.size < CHANGED_RATIO
    }

    /**
     * 比较中间核心区（row 4..11，避开顶边头像与邻座圆标压顶 row 0..3、底部光效文字 row 12..15）是否达到换牌级变动。
     */
    fun hasCoreAreaChanged(
        previous: IntArray,
        current: IntArray,
        minPoints: Int = DEFAULT_CORE_CHANGE_MIN_POINTS
    ): Boolean {
        if (previous.size != COLUMNS * ROWS || current.size != COLUMNS * ROWS) return false
        var changed = 0
        for (row in CORE_ROW_START until CORE_ROW_END_EXCLUSIVE) {
            val rowOffset = row * COLUMNS
            for (col in 0 until COLUMNS) {
                val index = rowOffset + col
                if (pixelChanged(previous[index], current[index])) {
                    changed++
                    if (changed >= minPoints) return true
                }
            }
        }
        return false
    }

    private fun pixelChanged(before: Int, after: Int): Boolean =
        channelChanged(before, after, 16) || channelChanged(before, after, 8) || channelChanged(before, after, 0)

    private fun channelChanged(before: Int, after: Int, shift: Int): Boolean =
        abs(((before shr shift) and 0xff) - ((after shr shift) and 0xff)) > CHANNEL_DELTA
}
