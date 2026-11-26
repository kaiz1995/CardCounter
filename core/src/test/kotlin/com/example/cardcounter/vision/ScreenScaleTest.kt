package com.example.cardcounter.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenScaleTest {
    private val white = 0xffffffff.toInt()
    private val pad = 0xff000000.toInt() or (114 shl 16) or (114 shl 8) or 114

    private fun constant(width: Int, height: Int, color: Int) = ArgbRaster(IntArray(width * height) { color }, width, height)

    private fun gradient(width: Int, height: Int) = object : Raster(width, height) {
        override fun rgb(x: Int, y: Int): Int = (((x * 3) and 0xff) shl 16) or (((y * 5) and 0xff) shl 8) or ((x + y) and 0xff)
    }

    @Test
    fun constantColourStaysConstantThroughAreaAndLinearScaling() {
        val region = ScreenScale.canvasRegion(constant(2_664, 1_236, 0x336699), 100, 50, 300, 200)
        assertEquals(300 * 200, region.size)
        region.forEach { assertEquals(0xff336699.toInt(), it) }
        val small = ScreenScale.canvasRegion(constant(640, 289, 0x336699), 0, 0, 1_280, 578)
        small.forEach { assertEquals(0xff336699.toInt(), it) }
    }

    /** 141x70 的出牌区：比例 320/141，高四舍五入成 159，上下各留 80 / 81 行灰边，左右不留。 */
    @Test
    fun letterboxCentresWithIntegerOffsetsAndPadsWith114() {
        val output = ScreenScale.letterbox(IntArray(141 * 70) { white }, 141, 70)
        assertEquals(320 * 320, output.size)
        for (y in 0 until 320) {
            val inside = y in 80 until 80 + 159
            for (x in listOf(0, 160, 319)) {
                assertEquals("x=$x y=$y", if (inside) white else pad, output[y * 320 + x])
            }
        }
    }

    /** 取整和 Python round 一致（银行家舍入）：640x5 缩 0.5 倍，高 2.5 取 2，居中偏移 159。 */
    @Test
    fun letterboxRoundsHalfToEvenLikeThePythonTrainingCode() {
        val output = ScreenScale.letterbox(IntArray(640 * 5) { white }, 640, 5)
        for (y in 0 until 320) assertEquals("y=$y", if (y in 159 until 161) white else pad, output[y * 320 + 100])
    }

    /** 手牌条带放在 870 见方黑底的第 540 行起；两种取法（整屏直接取 / 从已缩好的区域里切）像素完全一样。 */
    @Test
    fun handCanvasFromRegionEqualsHandCanvas() {
        val frame = gradient(2_664, 1_236)
        val direct = ScreenScale.handCanvas(frame, 180, 350, 1_050, 490)
        val region = ScreenScale.canvasRegion(frame, 0, 350, 1_280, 228)
        val sliced = ScreenScale.handCanvasFromRegion(region, 0, 350, 1_280, 180, 350, 1_050, 490)
        assertEquals(870 * 870, direct.size)
        assertArrayEquals(direct, sliced)
        for (y in 0 until 540) assertEquals(0xff000000.toInt(), direct[y * 870 + 400])
        for (y in 0 until 140) {
            assertEquals(region[y * 1_280 + 180 + 400], direct[(540 + y) * 870 + 400])
        }
    }

    @Test
    fun handInputIsTheBilinearResizeOfTheCanvas() {
        val canvas = IntArray(870 * 870) { 0xff102030.toInt() }
        val input = ScreenScale.handInput(canvas, 870)
        assertEquals(320 * 320, input.size)
        input.forEach { assertEquals(0xff102030.toInt(), it) }
    }
}
