package com.example.cardcounter.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import com.example.cardcounter.capture.PlayCrop
import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import java.io.File
import java.io.InputStream
import java.nio.FloatBuffer
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 一局真实对局里被旧牌角模板改判成 A 的两张单牌：新模型直接认对。
 * 输入是手机 publicPlayBitmap 同样的裁剪（PlayZone.yoloCrop(1280, 578)）、等比缩放、填充 114 之后的 320x320 RGB 原始字节（gzip）。
 * - nine_right_bottom：下家 RIGHT_BOTTOM 单张 9（当时被记成 A）。
 * - jack_top：对家 TOP 单张 J（当时被改判成 A，A 超量停机）。
 */
class RealRoiRecognitionTest {
    private val modelFile: File = listOf(
        File("src/main/assets/${OnnxRankDetector.MODEL_ASSET}"),
        File("app/src/main/assets/${OnnxRankDetector.MODEL_ASSET}")
    ).first { it.isFile }

    private fun detect(name: String): List<YoloDetection> {
        val rgb = requireNotNull(
            javaClass.getResourceAsStream("/w9_turn/$name.rgb.gz")?.use { GZIPInputStream(it).readBytes() }
        ) { "缺少输入图 $name.rgb.gz" }
        assertEquals(320 * 320 * 3, rgb.size)
        val pixels = IntArray(320 * 320) {
            0xff000000.toInt() or ((rgb[it * 3].toInt() and 0xff) shl 16) or
                ((rgb[it * 3 + 1].toInt() and 0xff) shl 8) or (rgb[it * 3 + 2].toInt() and 0xff)
        }
        return detectPixels(pixels)
    }

    private fun detectPixels(pixels: IntArray): List<YoloDetection> {
        val environment = OrtEnvironment.getEnvironment()
        ai.onnxruntime.OrtSession.SessionOptions().use { options ->
            environment.createSession(modelFile.readBytes(), options).use { session ->
                val size = RankModelContract.INPUT_SIZE.toLong()
                OnnxTensor.createTensor(
                    environment, FloatBuffer.wrap(argbToRankTensor(pixels)), longArrayOf(1, 3, size, size)
                ).use { input ->
                    session.run(mapOf(session.inputNames.first() to input)).use { result ->
                        val buffer = (result[0] as OnnxTensor).floatBuffer
                        return decodeRankOutput(FloatArray(buffer.remaining()).also { buffer.get(it) })
                    }
                }
            }
        }
    }

    private fun loadPngArgb(path: String): Triple<Int, Int, IntArray> {
        val image = requireNotNull(javaClass.getResourceAsStream(path)) { "缺少夹具 $path" }.use { stream ->
            Class.forName("javax.imageio.ImageIO")
                .getMethod("read", InputStream::class.java)
                .invoke(null, stream)
        }
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = image.javaClass.getMethod(
            "getRGB",
            Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            IntArray::class.java, Int::class.java, Int::class.java
        ).invoke(image, 0, 0, width, height, null, 0, width) as IntArray
        return Triple(width, height, pixels)
    }

    private fun detectStripCrop(
        stripWidth: Int,
        stripPixels: IntArray,
        stripLeftX: Int,
        stripTopY: Int,
        crop: PlayCrop
    ): RankCounts {
        val size = RankModelContract.INPUT_SIZE
        val scale = minOf(size.toFloat() / crop.width, size.toFloat() / crop.height)
        val scaledW = (crop.width * scale).roundToInt().coerceAtLeast(1)
        val scaledH = (crop.height * scale).roundToInt().coerceAtLeast(1)
        val offsetX = ((size - scaledW) / 2f).roundToInt()
        val offsetY = ((size - scaledH) / 2f).roundToInt()
        val pixels = IntArray(size * size) { 0xff727272.toInt() }
        val srcLeft = crop.left - stripLeftX
        val srcTop = crop.top - stripTopY

        for (dy in 0 until scaledH) {
            val sy = (dy + 0.5f) * crop.height / scaledH - 0.5f
            val y0 = sy.toInt().coerceIn(0, crop.height - 1)
            val y1 = (y0 + 1).coerceIn(0, crop.height - 1)
            val fy = (sy - y0).coerceIn(0f, 1f)
            for (dx in 0 until scaledW) {
                val sx = (dx + 0.5f) * crop.width / scaledW - 0.5f
                val x0 = sx.toInt().coerceIn(0, crop.width - 1)
                val x1 = (x0 + 1).coerceIn(0, crop.width - 1)
                val fx = (sx - x0).coerceIn(0f, 1f)
                val c00 = stripPixels[(srcTop + y0) * stripWidth + (srcLeft + x0)]
                val c10 = stripPixels[(srcTop + y0) * stripWidth + (srcLeft + x1)]
                val c01 = stripPixels[(srcTop + y1) * stripWidth + (srcLeft + x0)]
                val c11 = stripPixels[(srcTop + y1) * stripWidth + (srcLeft + x1)]
                val r = bilerp((c00 shr 16) and 0xff, (c10 shr 16) and 0xff, (c01 shr 16) and 0xff, (c11 shr 16) and 0xff, fx, fy)
                val g = bilerp((c00 shr 8) and 0xff, (c10 shr 8) and 0xff, (c01 shr 8) and 0xff, (c11 shr 8) and 0xff, fx, fy)
                val b = bilerp(c00 and 0xff, c10 and 0xff, c01 and 0xff, c11 and 0xff, fx, fy)
                pixels[(offsetY + dy) * size + (offsetX + dx)] = 0xff000000.toInt() or (r shl 16) or (g shl 8) or b
            }
        }
        val counts = Rank.entries.associateWith { 0 }.toMutableMap()
        detectPixels(pixels).forEach { detection ->
            counts[detection.rank] = counts.getValue(detection.rank) + 1
        }
        return RankCounts.of(counts)
    }

    private fun bilerp(v00: Int, v10: Int, v01: Int, v11: Int, fx: Float, fy: Float): Int {
        val top = v00 + (v10 - v00) * fx
        val bottom = v01 + (v11 - v01) * fx
        return (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255)
    }

    @Test
    fun singleNineOfTheNextSeatIsNine() {
        assertEquals(listOf(Rank.NINE), detect("nine_right_bottom").map { it.rank })
    }

    @Test
    fun singleJackOfTheOppositeSeatIsJack() {
        assertEquals(listOf(Rank.JACK), detect("jack_top").map { it.rank })
    }

    @Test
    fun expandedCropCapturesSevenCardHandWithBigJokerInRightTop() {
        val (stripWidth, stripHeight, stripPixels) = loadPngArgb("/w12_right_top_joker_6k.png")
        val stripLeftX = 795
        val stripTopY = 62
        assertEquals(306, stripWidth)
        assertEquals(56, stripHeight)

        val fixedCrop = PlayZone.RIGHT_TOP.yoloCrop(1_280, 578)
        val fixedCounts = detectStripCrop(stripWidth, stripPixels, stripLeftX, stripTopY, fixedCrop)
        assertEquals(RankCounts.of(mapOf(Rank.KING to 6)), fixedCounts)

        val expandedCrop = PlayZone.RIGHT_TOP.expandedYoloCrop(1_280, 578) { x, top, bottom ->
            var whitePixels = 0
            for (y in top until bottom) {
                val rgb = stripPixels[(y - stripTopY) * stripWidth + (x - stripLeftX)]
                val r = (rgb shr 16) and 0xff
                val g = (rgb shr 8) and 0xff
                val b = rgb and 0xff
                if (r >= 190 && g >= 190 && b >= 190) {
                    whitePixels++
                    if (whitePixels >= 8) return@expandedYoloCrop true
                }
            }
            false
        }
        val expandedCounts = detectStripCrop(stripWidth, stripPixels, stripLeftX, stripTopY, expandedCrop)
        assertEquals(RankCounts.of(mapOf(Rank.BIG_JOKER to 1, Rank.KING to 6)), expandedCounts)
    }
}
