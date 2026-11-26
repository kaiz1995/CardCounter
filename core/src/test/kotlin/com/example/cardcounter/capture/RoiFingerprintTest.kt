package com.example.cardcounter.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoiFingerprintTest {
    @Test
    fun smallChangesStayStillAndBigOnesDoNot() {
        val base = RoiFingerprint.of(PlayZone.TOP) { x, y -> (x * 3 + y * 5) and 0xffffff }
        fun changed(count: Int) = base.copyOf().also { for (i in 0 until count) it[i] = it[i] xor 0x800000 }
        assertTrue(RoiFingerprint.isStill(base, base.copyOf()))
        assertTrue(RoiFingerprint.isStill(base, changed(10)))
        assertFalse(RoiFingerprint.isStill(base, changed(100)))
        assertFalse(RoiFingerprint.isStill(base, base.copyOf(10)))
    }

    @Test
    fun samplesStayInsideTheZoneCrop() {
        PlayZone.entries.forEach { zone ->
            val crop = zone.yoloCrop(1_280, 578)
            RoiFingerprint.of(zone) { x, y ->
                assertTrue(x in crop.left until crop.left + crop.width)
                assertTrue(y in crop.top until crop.top + crop.height)
                0
            }
        }
    }

    @Test
    fun channelDeltaSixtyAbsorbsCardEffectGlowWhileRejectingCardSwap() {
        val base = IntArray(RoiFingerprint.COLUMNS * RoiFingerprint.ROWS) { 0x404040 }
        // 模拟底部“5张/少于10张”亮光逐渐消退：每个变化点通道差仅 50（<= 60），应仍算静止。
        val mildGlow = base.copyOf().also {
            for (i in 0 until 80) it[i] = 0x404040 + (50 shl 8)
        }
        assertTrue("通道差 50（<= 60）的光效渐变不应打断静止", RoiFingerprint.isStill(base, mildGlow))

        // 模拟真实换牌：超过 3% 采样点变化超过 60（这里取 80），应判不静止。
        val swapped = base.copyOf().also {
            for (i in 0 until 40) it[i] = 0x404040 + (80 shl 16)
        }
        assertFalse("超过 3% 的像素通道差 80 必须触发画面变化", RoiFingerprint.isStill(base, swapped))
    }

    @Test
    fun coreAreaDiffIgnoresTopAndBottomEdgesAndDetectsCardSwap() {
        val base = IntArray(RoiFingerprint.COLUMNS * RoiFingerprint.ROWS) { 0x202020 }
        fun changedAt(rows: IntRange, cols: Int): IntArray = base.copyOf().also { arr ->
            for (r in rows) {
                for (c in 0 until cols) {
                    arr[r * RoiFingerprint.COLUMNS + c] = 0x202020 + (90 shl 16)
                }
            }
        }
        // 顶 4 行（row 0..3，头像框边缘、邻座圆标压顶、特效粒子）或底 4 行（row 12..15，“少于10张/出牌”提示）不应判核心区换牌。
        assertFalse(RoiFingerprint.hasCoreAreaChanged(base, changedAt(0..3, 20)))
        assertFalse(RoiFingerprint.hasCoreAreaChanged(base, changedAt(12..15, 30)))
        // 中间核心区（row 4..11）默认门限 4 点（COVERED 用）：5 点换花色检出，2~3 点微扰拦截。
        assertTrue(RoiFingerprint.hasCoreAreaChanged(base, changedAt(9..9, 5)))
        assertFalse(RoiFingerprint.hasCoreAreaChanged(base, changedAt(5..5, 3)))
        // 提高到 STABLE 门限（8 点）时：7 点拦截，8 点及以上检出。
        assertFalse(RoiFingerprint.hasCoreAreaChanged(base, changedAt(9..9, 7), minPoints = 8))
        assertTrue(RoiFingerprint.hasCoreAreaChanged(base, changedAt(9..9, 8), minPoints = 8))
    }
}
