package com.example.cardcounter.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.cardcounter.domain.Rank
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机和电脑评测必须是同一个模型、同一套预处理和解码。
 * 期望值由 D:\CardCounterData\w8_golden\make_golden.py 用电脑端 eval_crops 的 decode_new 生成
 * （4 张截图 × 6 个出牌区 = 24 组，输入是已 letterbox 的 320×320 RGB 原始字节，gzip 压缩，无损）。
 * 本测试在桌面 JVM 上用同版本 ONNX Runtime 加载 assets 里的同一个 onnx，再走 :core 的解码。
 */
class RankModelConsistencyTest {
    private val modelFile: File = listOf(
        File("src/main/assets/${OnnxRankDetector.MODEL_ASSET}"),
        File("app/src/main/assets/${OnnxRankDetector.MODEL_ASSET}")
    ).first { it.isFile }

    private fun resourceText(path: String): String =
        requireNotNull(javaClass.getResourceAsStream(path)) { "缺少测试资源 $path" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun withSession(block: (OrtEnvironment, OrtSession) -> Unit) {
        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            environment.createSession(modelFile.readBytes(), options).use { session ->
                block(environment, session)
            }
        }
    }

    private fun run(environment: OrtEnvironment, session: OrtSession, tensor: FloatArray): FloatArray {
        val size = RankModelContract.INPUT_SIZE.toLong()
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(tensor), longArrayOf(1, 3, size, size)).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { result ->
                val output = result[0] as OnnxTensor
                val buffer = output.floatBuffer
                return FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }
    }

    @Test
    fun modelAssetIsTheExpectedFile() {
        assertEquals(MODEL_SIZE, modelFile.length())
        val digest = MessageDigest.getInstance("SHA-256").digest(modelFile.readBytes())
        assertEquals(MODEL_SHA256, digest.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun modelShapesMatchContract() {
        withSession { _, session ->
            assertEquals(listOf("images"), session.inputNames.toList())
            assertEquals(listOf("output0"), session.outputNames.toList())
            val input = session.inputInfo.getValue("images").info as ai.onnxruntime.TensorInfo
            val output = session.outputInfo.getValue("output0").info as ai.onnxruntime.TensorInfo
            assertEquals(listOf(1L, 3L, 320L, 320L), input.shape.toList())
            assertEquals(
                listOf(1L, RankModelContract.OUTPUT_ROWS.toLong(), RankModelContract.CANDIDATES.toLong()),
                output.shape.toList()
            )
            assertEquals(15, RankModelContract.CLASS_COUNT)
            assertEquals(Rank.entries.size, RankModelContract.CLASS_COUNT)
            assertEquals(19, RankModelContract.OUTPUT_ROWS)
            assertEquals(2100, RankModelContract.CANDIDATES)
        }
    }

    @Test
    fun goldenRankOrderMatchesRankEnum() {
        val golden = JSONObject(resourceText("/w8_golden/golden.json"))
        val ranks = golden.getJSONArray("ranks")
        assertEquals(Rank.entries.map { it.name }, (0 until ranks.length()).map { ranks.getString(it) })
        assertEquals(MODEL_SHA256, golden.getString("model_sha256"))
        assertEquals(0.25, golden.getDouble("conf"), 0.0)
        assertEquals(0.45, golden.getDouble("nms_iou"), 0.0)
    }

    @Test
    fun detectionsMatchPythonOnAll24ScreenshotZones() {
        val golden = JSONObject(resourceText("/w8_golden/golden.json"))
        val cases = golden.getJSONArray("cases")
        assertEquals(24, cases.length())
        var compared = 0
        var maxConfidenceDiff = 0.0
        var maxBoxDiff = 0.0
        withSession { environment, session ->
            for (caseIndex in 0 until cases.length()) {
                val case = cases.getJSONObject(caseIndex)
                val id = case.getString("id")
                val rgb = requireNotNull(
                    javaClass.getResourceAsStream("/w8_golden/$id.rgb.gz")?.use { GZIPInputStream(it).readBytes() }
                ) { "缺少输入图 $id.rgb.gz" }
                assertEquals("$id 字节数", 320 * 320 * 3, rgb.size)
                val pixels = IntArray(320 * 320) {
                    0xff000000.toInt() or ((rgb[it * 3].toInt() and 0xff) shl 16) or
                        ((rgb[it * 3 + 1].toInt() and 0xff) shl 8) or (rgb[it * 3 + 2].toInt() and 0xff)
                }
                val detections = decodeRankOutput(run(environment, session, argbToRankTensor(pixels)))

                val expected = case.getJSONArray("detections")
                assertEquals("$id 检测框数", expected.length(), detections.size)
                for (i in 0 until expected.length()) {
                    val want = expected.getJSONObject(i)
                    val got = detections[i]
                    assertEquals("$id #$i 类别", want.getInt("class"), got.rank.ordinal)
                    assertEquals("$id #$i 点数", want.getString("rank"), got.rank.name)
                    assertEquals("$id #$i 置信度", want.getDouble("conf"), got.confidence.toDouble(), 1e-3)
                    val box = want.getJSONArray("box")
                    val actual = doubleArrayOf(
                        (got.centerX - got.width / 2f).toDouble(),
                        (got.centerY - got.height / 2f).toDouble(),
                        (got.centerX + got.width / 2f).toDouble(),
                        (got.centerY + got.height / 2f).toDouble()
                    )
                    maxConfidenceDiff = maxOf(maxConfidenceDiff, abs(want.getDouble("conf") - got.confidence.toDouble()))
                    for (k in 0 until 4) {
                        val diff = abs(box.getDouble(k) - actual[k])
                        maxBoxDiff = maxOf(maxBoxDiff, diff)
                        assertTrue("$id #$i 框坐标[$k] 差 $diff 像素", diff < 0.5)
                    }
                    compared++
                }

                // 点数计数 = eval_crops.SHOT_CASES 里肉眼确认的期望。
                val expectedCounts = case.getJSONObject("expected_counts")
                val want = expectedCounts.keys().asSequence().associate { Rank.valueOf(it) to expectedCounts.getInt(it) }
                assertEquals("$id 点数计数", want, detections.groupingBy { it.rank }.eachCount())
            }
        }
        println("CONSISTENCY cases=${cases.length()} boxes=$compared maxConfidenceDiff=$maxConfidenceDiff maxBoxDiffPx=$maxBoxDiff")
        assertEquals(20, compared)
    }

    private companion object {
        const val MODEL_SHA256 = "d206890df69e44673e8392f90e1211fdc5a66fe45f864840fff62e4bd5df5bf3"
        const val MODEL_SIZE = 10_450_050L
    }
}
