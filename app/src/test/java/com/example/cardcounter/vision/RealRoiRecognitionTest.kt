package com.example.cardcounter.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import com.example.cardcounter.domain.Rank
import java.io.File
import java.nio.FloatBuffer
import java.util.zip.GZIPInputStream
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

    @Test
    fun singleNineOfTheNextSeatIsNine() {
        assertEquals(listOf(Rank.NINE), detect("nine_right_bottom").map { it.rank })
    }

    @Test
    fun singleJackOfTheOppositeSeatIsJack() {
        assertEquals(listOf(Rank.JACK), detect("jack_top").map { it.rank })
    }
}
