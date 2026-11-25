package com.example.cardcounter.capture

/**
 * 红底黄字“出牌”倒计时圆标：谁的圆标亮着，就轮到谁出牌。
 * 坐标是 1280x578 画布坐标，与手机取色 CaptureProbeService.rgb 用同一套换算；
 * 阈值在一局 118 张真实截图上与模板比对结果 0 误差。
 */
object TurnBadge {
    /** 挂王时牌还摆着，圆标会往上偏，所以中心依次试这几个纵向偏移。 */
    private val Y_OFFSETS = intArrayOf(0, -12, -24, -30)
    private const val HALF_WIDTH = 16
    private const val HALF_HEIGHT = 12
    private const val STEP = 2

    /** [pixel] 返回画布坐标 (x, y) 处的 0xRRGGBB。 */
    fun isShown(zone: PlayZone, pixel: (x: Int, y: Int) -> Int): Boolean =
        Y_OFFSETS.any { offset -> looksLikeBadge(pixel, zone.badgeX, zone.badgeY + offset) }

    private fun looksLikeBadge(pixel: (Int, Int) -> Int, centerX: Int, centerY: Int): Boolean {
        var red = 0
        var yellow = 0
        var samples = 0
        var y = centerY - HALF_HEIGHT
        while (y <= centerY + HALF_HEIGHT) {
            var x = centerX - HALF_WIDTH
            while (x <= centerX + HALF_WIDTH) {
                val rgb = pixel(x, y)
                val r = (rgb shr 16) and 0xff
                val g = (rgb shr 8) and 0xff
                val b = rgb and 0xff
                samples++
                if (r >= 140 && g <= 70 && b <= 70) red++
                if (r >= 200 && g >= 170 && b <= 120) yellow++
                x += STEP
            }
            y += STEP
        }
        return red * 10 >= samples * 3 && yellow * 5 >= samples
    }
}

/**
 * 同一家连续 [CONSECUTIVE] 次采样都看到圆标、而且这一区桌面已空，才算“轮到他”；每次只触发一次。
 * 挂王时上一手还摆在桌上圆标就亮了，这时不算：否则画面闪一下再交出同一手，会被当成新的一手重记。
 */
class TurnTracker {
    private val consecutive = IntArray(PlayZone.entries.size)
    private val fired = BooleanArray(PlayZone.entries.size)

    /** 返回 true 表示这一次采样刚确认“轮到这一家”。 */
    @Synchronized
    fun update(zone: PlayZone, shown: Boolean, zoneEmpty: Boolean): Boolean {
        val index = zone.ordinal
        if (!shown || !zoneEmpty) {
            consecutive[index] = 0
            fired[index] = false
            return false
        }
        consecutive[index]++
        if (consecutive[index] < CONSECUTIVE || fired[index]) return false
        fired[index] = true
        return true
    }

    @Synchronized
    fun reset() {
        consecutive.fill(0)
        fired.fill(false)
    }

    private companion object {
        const val CONSECUTIVE = 2
    }
}
