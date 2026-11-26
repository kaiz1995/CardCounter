package com.example.cardcounter.vision

import java.util.zip.GZIPInputStream
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机的缩放和训练、评测端（cv2）逐像素一致：输入是 2664x1236 整屏截图里手牌和 RIGHT_BOTTOM 出牌区用到的那一块原始像素，
 * 参考值由 make_reference.py 用 cv2.INTER_AREA 到 1280x578、cv2.INTER_LINEAR 到 320 生成。最大差 ≤ 1（浮点取整的出入）。
 */
class ScreenScaleReferenceTest {
    private fun resource(name: String): ByteArray =
        GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/w10_scale/$name")) { "缺少测试资源 $name" })
            .use { it.readBytes() }

    private val meta = JSONObject(
        requireNotNull(javaClass.getResourceAsStream("/w10_scale/meta.json")).use { it.readBytes().toString(Charsets.UTF_8) }
    )

    /** 整屏尺寸的图，只有 [box] 里有像素；读到范围外说明缩放用了不该用的源像素，直接失败。 */
    private class CroppedRaster(width: Int, height: Int, private val box: IntArray, private val rgb: ByteArray) :
        Raster(width, height) {
        override fun rgb(x: Int, y: Int): Int {
            val bx = x - box[0]
            val by = y - box[1]
            check(bx in 0 until box[2] && by in 0 until box[3]) { "读到了参考数据之外的像素 ($x,$y)" }
            val offset = (by * box[2] + bx) * 3
            return ((rgb[offset].toInt() and 0xff) shl 16) or
                ((rgb[offset + 1].toInt() and 0xff) shl 8) or
                (rgb[offset + 2].toInt() and 0xff)
        }
    }

    private fun raster(kind: String): Raster {
        val box = meta.getJSONArray("${kind}Source").let { IntArray(4) { index -> it.getInt(index) } }
        return CroppedRaster(meta.getInt("screenWidth"), meta.getInt("screenHeight"), box, resource("source_$kind.rgb.gz"))
    }

    private fun maxDifference(actual: IntArray, expected: ByteArray): Int {
        assertEquals(expected.size, actual.size * 3)
        var max = 0
        for (index in actual.indices) {
            val color = actual[index]
            max = maxOf(
                max,
                abs(((color shr 16) and 0xff) - (expected[index * 3].toInt() and 0xff)),
                abs(((color shr 8) and 0xff) - (expected[index * 3 + 1].toInt() and 0xff)),
                abs((color and 0xff) - (expected[index * 3 + 2].toInt() and 0xff))
            )
        }
        return max
    }

    @Test
    fun handInputMatchesTheTrainingPreprocessing() {
        val (left, top, right, bottom) = meta.getJSONArray("hand").let { IntArray(4) { index -> it.getInt(index) } }
        val canvas = ScreenScale.handCanvas(raster("hand"), left, top, right, bottom)
        val input = ScreenScale.handInput(canvas, right - left)
        assertEquals(320 * 320, input.size)
        val difference = maxDifference(input, resource("ref_hand.rgb.gz"))
        assertTrue("手牌输入和 cv2 最大差 $difference，应 ≤ 1", difference <= 1)
    }

    @Test
    fun playZoneInputMatchesTheTrainingPreprocessing() {
        val (left, top, width, height) = meta.getJSONArray("zone").let { IntArray(4) { index -> it.getInt(index) } }
        val input = ScreenScale.playZoneInput(raster("zone"), left, top, width, height)
        assertEquals(320 * 320, input.size)
        val difference = maxDifference(input, resource("ref_zone.rgb.gz"))
        assertTrue("出牌区输入和 cv2 最大差 $difference，应 ≤ 1", difference <= 1)
    }

    private operator fun IntArray.component1() = this[0]
    private operator fun IntArray.component2() = this[1]
    private operator fun IntArray.component3() = this[2]
    private operator fun IntArray.component4() = this[3]
}
