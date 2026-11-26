package com.example.cardcounter.capture

/**
 * 红底黄字“出牌”倒计时圆标：谁的圆标亮着，就轮到谁出牌。
 * 坐标是 1280x578 画布坐标，与手机取色 CaptureProbeService.rgb 用同一套换算；
 * 阈值在一局 118 张真实截图上与模板比对结果 0 误差。
 */
object TurnBadge {
    /**
     * 挂王时圆标会往上偏，所以中心依次试这几个纵向偏移：偏上也认得出，
     * 但牌没收走前不算轮到他（见 [TurnTracker]）。
     */
    private val Y_OFFSETS = intArrayOf(0, -12, -24, -30)
    private const val HALF_WIDTH = 16
    private const val HALF_HEIGHT = 12
    private const val STEP = 2

    /**
     * 一个区的圆标取色窗：合并所有 [Y_OFFSETS] 之后取到的像素的外接矩形（1280x578 画布坐标）。
     * [right]、[bottom] 是最后一个会取到的像素坐标（含）。悬浮窗不许盖住它，否则截屏里认不到圆标。
     */
    data class Window(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun window(zone: PlayZone): Window = Window(
        left = zone.badgeX - HALF_WIDTH,
        top = zone.badgeY + Y_OFFSETS.min() - HALF_HEIGHT,
        right = zone.badgeX + HALF_WIDTH,
        bottom = zone.badgeY + Y_OFFSETS.max() + HALF_HEIGHT
    )

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
                if (isBadgeRed(r, g, b)) red++
                if (isBadgeYellow(r, g, b)) yellow++
                x += STEP
            }
            y += STEP
        }
        return red * 100 >= samples * 43 && yellow * 5 >= samples
    }

    /** 红色圆标底色：相对色差 + 低绿蓝比，烧牌/开点整屏压暗时仍能识别，同时不被暖黄色或半透明移动边误触。 */
    internal fun isBadgeRed(r: Int, g: Int, b: Int): Boolean =
        r >= 65 && r - g >= 45 && r - b >= 45 && g * 2 <= r && b * 2 <= r

    /** 黄色“出牌”字：红绿显著高于蓝、且红绿接近，兼容压暗画面，排除白/灰/绿/纯红。 */
    internal fun isBadgeYellow(r: Int, g: Int, b: Int): Boolean =
        r >= 85 && g >= 70 && r - b >= 40 && g - b >= 30 && r - g <= 75
}

/** 单次 [TurnTracker.track] 更新结果：[turned] 刚确认轮到这一家；[longCleared] 连续空桌满 1.5s。 */
data class TurnUpdate(val turned: Boolean, val longCleared: Boolean)

/**
 * 同一家连续 [CONSECUTIVE] 次采样都看到圆标、而且这一区桌面已空、没有待兜底交出的牌（[RoiPlayEventCache.hasUndelivered]），
 * 才算“轮到他”；每次只触发一次。
 * 同时跟踪这一区连续空桌（桌面可见且无牌无待兜底）时长：满 [LONG_CLEAR_MS]（1.5 秒）触发一次 [TurnUpdate.longCleared]，
 * 作为圆标漏判的兜底解锁；300ms 等瞬时闪空不会触发。
 */
class TurnTracker(private val longClearMs: Long = LONG_CLEAR_MS) {
    private val consecutive = IntArray(PlayZone.entries.size)
    private val fired = BooleanArray(PlayZone.entries.size)
    private val emptySinceMs = LongArray(PlayZone.entries.size) { -1L }
    private val longClearFired = BooleanArray(PlayZone.entries.size)

    /** 返回 true 表示这一次采样刚确认“轮到这一家”。 */
    @Synchronized
    fun update(zone: PlayZone, shown: Boolean, zoneEmpty: Boolean): Boolean =
        track(zone, shown, zoneEmpty, timestampMs = null, tableVisible = true).turned

    /** 完整跟踪：同时推进圆标连续计数与连续空桌长清桌计时。 */
    @Synchronized
    fun track(
        zone: PlayZone,
        shown: Boolean,
        zoneEmpty: Boolean,
        timestampMs: Long?,
        tableVisible: Boolean = true
    ): TurnUpdate {
        val index = zone.ordinal
        val longCleared = if (tableVisible && zoneEmpty && timestampMs != null) {
            if (emptySinceMs[index] < 0L) {
                emptySinceMs[index] = timestampMs
                false
            } else if (!longClearFired[index] && timestampMs - emptySinceMs[index] >= longClearMs) {
                longClearFired[index] = true
                true
            } else {
                false
            }
        } else {
            emptySinceMs[index] = -1L
            longClearFired[index] = false
            false
        }

        if (!tableVisible || !shown || !zoneEmpty) {
            consecutive[index] = 0
            fired[index] = false
            return TurnUpdate(turned = false, longCleared = longCleared)
        }
        consecutive[index]++
        if (consecutive[index] < CONSECUTIVE || fired[index]) {
            return TurnUpdate(turned = false, longCleared = longCleared)
        }
        fired[index] = true
        return TurnUpdate(turned = true, longCleared = longCleared)
    }

    @Synchronized
    fun reset() {
        consecutive.fill(0)
        fired.fill(false)
        emptySinceMs.fill(-1L)
        longClearFired.fill(false)
    }

    companion object {
        const val CONSECUTIVE = 2
        const val LONG_CLEAR_MS = 1_500L
    }
}
