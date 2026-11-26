package com.example.cardcounter.capture

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.cardcounter.domain.HandAutoLock
import com.example.cardcounter.domain.HandReading
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.vision.ArgbRaster
import com.example.cardcounter.vision.OnnxRankDetector
import com.example.cardcounter.vision.RankModelContract
import com.example.cardcounter.vision.ScreenScale
import com.example.cardcounter.vision.argbToRankTensor
import com.example.cardcounter.vision.decodeRankOutput
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * 手牌链路 Python vs Kotlin 一致性守卫：
 * tools/dataset/hand_vision.py（及 hand_eval.py）与手机端 Kotlin 同一套实现必须在真实 12 帧手牌序列上逐帧零差异。
 *
 * 覆盖环节：
 * 1. isTableVisible + 12x8 亮度指纹变化率与 scene 切换；
 * 2. HandCardPositionDetector 两排张数与总数检测；
 * 3. ScreenScale.handCanvas (870x870) -> handInput (320x320) -> ONNX Runtime -> decodeRankOutput；
 * 4. HandFrameMerger 多帧合并 + HandPositionVoter 张数众数投票 + HandAutoLock 连续稳定锁定判定。
 */
class HandGoldenConsistencyTest {
    private val modelFile: File = listOf(
        File("src/main/assets/${OnnxRankDetector.MODEL_ASSET}"),
        File("app/src/main/assets/${OnnxRankDetector.MODEL_ASSET}")
    ).first { it.isFile }

    private fun loadExpected(): JSONObject {
        val stream = requireNotNull(javaClass.getResourceAsStream("/hand_golden/expected.json")) {
            "缺少 /hand_golden/expected.json"
        }
        return JSONObject(stream.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    private fun loadPixels(id: String): IntArray {
        val stream = requireNotNull(javaClass.getResourceAsStream("/hand_golden/$id.png")) {
            "缺少测试帧 /hand_golden/$id.png"
        }
        val image = stream.use { s ->
            Class.forName("javax.imageio.ImageIO")
                .getMethod("read", InputStream::class.java)
                .invoke(null, s)
        }
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        assertEquals("$id 宽", ScreenScale.CANVAS_WIDTH, width)
        assertEquals("$id 高", ScreenScale.CANVAS_HEIGHT, height)
        return image.javaClass.getMethod(
            "getRGB",
            Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            IntArray::class.java, Int::class.java, Int::class.java
        ).invoke(image, 0, 0, width, height, null, 0, width) as IntArray
    }

    /** 与 CaptureProbeService.handSignature 完全相同的取样逻辑。 */
    private fun handSignature(pixels: IntArray): IntArray {
        val width = ScreenScale.CANVAS_WIDTH
        val height = ScreenScale.CANVAS_HEIGHT
        val area = HandRegions.signatureArea(width, height)
        val result = IntArray(SIGNATURE_COLS * SIGNATURE_ROWS)
        for (index in result.indices) {
            val col = index % SIGNATURE_COLS
            val row = index / SIGNATURE_COLS
            val x = area.left + (area.right - area.left - 1) * col / (SIGNATURE_COLS - 1)
            val y = area.top + (height - area.top - 1) * row / (SIGNATURE_ROWS - 1)
            val rgb = pixels[y * width + x]
            val r = (rgb shr 16) and 0xff
            val g = (rgb shr 8) and 0xff
            val b = rgb and 0xff
            result[index] = (r * 30 + g * 59 + b * 11) / 100
        }
        return result
    }

    /** 与 HandCardPositionDetector.snapshot 完全相同的取样逻辑。 */
    private fun positionLuma(pixels: IntArray): IntArray {
        val width = ScreenScale.CANVAS_WIDTH
        val height = ScreenScale.CANVAS_HEIGHT
        val result = IntArray(width * height)
        val areas = listOf(
            HandRegions.topEdgeRow,
            HandRegions.bottomEdgeRow,
            HandRegions.bottomBorderRows
        )
        for (area in areas) {
            for (y in area.top until area.bottom) {
                for (x in area.left until area.right) {
                    val rgb = pixels[y * width + x]
                    val r = (rgb shr 16) and 0xff
                    val g = (rgb shr 8) and 0xff
                    val b = rgb and 0xff
                    result[y * width + x] = (r * 30 + g * 59 + b * 11) / 100
                }
            }
        }
        return result
    }

    private fun jsonCounts(obj: JSONObject): RankCounts {
        val map = HashMap<Rank, Int>()
        for (key in obj.keys()) {
            map[Rank.valueOf(key)] = obj.getInt(key)
        }
        return RankCounts.of(map)
    }

    @Test
    fun handPipelineMatchesPythonOnAll12GoldenFrames() {
        val expected = loadExpected()
        val frames = expected.getJSONArray("frames")
        assertTrue("至少需要 10 帧手牌黄金测试帧，实际 ${frames.length()}", frames.length() >= 10)

        val posDetector = HandCardPositionDetector()
        val merger = HandFrameMerger()
        val voter = HandPositionVoter()
        val autoLock = HandAutoLock()
        val checkpointLock = HandAutoLock()
        var prevSig: IntArray? = null
        var scene = 1L

        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            environment.createSession(modelFile.readBytes(), options).use { session ->
                val inputName = session.inputNames.first()
                for (i in 0 until frames.length()) {
                    val want = frames.getJSONObject(i)
                    val id = want.getString("id")
                    val pixels = loadPixels(id)
                    val raster = ArgbRaster(pixels, ScreenScale.CANVAS_WIDTH, ScreenScale.CANVAS_HEIGHT)

                    assertEquals("$id table_visible", want.getBoolean("table_visible"), isTableVisible(raster))

                    val sig = handSignature(pixels)
                    val chg = handChangePercent(prevSig, sig)
                    if (want.isNull("sig_change_percent")) {
                        assertEquals("$id sig_change_percent 应为 null", null, chg)
                    } else {
                        assertEquals("$id sig_change_percent", want.getDouble("sig_change_percent"), chg!!, 1e-3)
                        if (chg > STABLE_CHANGE_PERCENT) scene += 1L
                    }
                    prevSig = sig
                    assertEquals("$id scene", want.getLong("scene"), scene)

                    val luma = positionLuma(pixels)
                    val rows = posDetector.rowCounts(luma)
                    val posCount = posDetector.detect(rows)
                    val wantRows = want.getJSONArray("pos_rows")
                    assertEquals("$id topRow", if (wantRows.isNull(0)) null else wantRows.getInt(0), rows.first)
                    assertEquals("$id bottomRow", if (wantRows.isNull(1)) null else wantRows.getInt(1), rows.second)
                    val wantPos = if (want.isNull("pos_count")) null else want.getInt("pos_count")
                    assertEquals("$id pos_count", wantPos, posCount)

                    val canvas870 = ScreenScale.handCanvas(
                        raster,
                        HandRegions.YOLO_LEFT,
                        HandRegions.YOLO_TOP,
                        HandRegions.YOLO_RIGHT,
                        HandRegions.YOLO_BOTTOM
                    )
                    val rgb320 = ScreenScale.handInput(canvas870, HandRegions.YOLO_RIGHT - HandRegions.YOLO_LEFT)
                    val tensor = argbToRankTensor(rgb320)
                    val size = RankModelContract.INPUT_SIZE.toLong()
                    val detections = OnnxTensor.createTensor(
                        environment,
                        FloatBuffer.wrap(tensor),
                        longArrayOf(1, 3, size, size)
                    ).use { input ->
                        session.run(mapOf(inputName to input)).use { result ->
                            val buf = (result[0] as OnnxTensor).floatBuffer
                            val raw = FloatArray(buf.remaining()).also { buf.get(it) }
                            decodeRankOutput(raw)
                        }
                    }

                    val wantDets = want.getJSONArray("detections")
                    val sortedDetections = detections.sortedWith(
                        compareBy<com.example.cardcounter.vision.YoloDetection>(
                            { kotlin.math.round(it.centerY / 12f).toInt() },
                            { it.centerX },
                            { it.rank.ordinal }
                        )
                    )
                    assertEquals("$id 检测框数量", wantDets.length(), sortedDetections.size)
                    for (j in 0 until wantDets.length()) {
                        val wd = wantDets.getJSONObject(j)
                        val gd = sortedDetections[j]
                        assertEquals("$id #$j 类别", wd.getString("rank"), gd.rank.name)
                        assertEquals("$id #$j conf", wd.getDouble("conf"), gd.confidence.toDouble(), 0.02)
                        assertTrue("$id #$j cx 差超 0.5px", abs(wd.getDouble("cx") - gd.centerX) < 0.5)
                        assertTrue("$id #$j cy 差超 0.5px", abs(wd.getDouble("cy") - gd.centerY) < 0.5)
                        assertTrue("$id #$j w 差超 0.5px", abs(wd.getDouble("w") - gd.width) < 0.5)
                        assertTrue("$id #$j h 差超 0.5px", abs(wd.getDouble("h") - gd.height) < 0.5)
                    }

                    val merge = merger.add(scene, detections)
                    val votedPos = voter.add(scene, posCount)
                    val reading = HandReading(merge.merged, votedPos)
                    autoLock.observe(reading)
                    checkpointLock.observe(reading)

                    assertEquals("$id single", jsonCounts(want.getJSONObject("single")), merge.single)
                    assertEquals("$id merged", jsonCounts(want.getJSONObject("merged")), merge.merged)
                    assertEquals("$id merged_frames", want.getInt("merged_frames"), merge.frames)
                    assertEquals("$id merged_fell_back", want.getBoolean("merged_fell_back"), merge.fellBack)
                    val wantVoted = if (want.isNull("voted_position")) null else want.getInt("voted_position")
                    assertEquals("$id voted_position", wantVoted, votedPos)
                    assertEquals("$id auto_lock latest", reading, autoLock.latest())

                    if (i == 3) {
                        val wq = want.getJSONObject("auto_lock_qualified")
                        val lockedAt4 = checkpointLock.lock()!!
                        assertEquals("$id lock@4 cards", jsonCounts(wq.getJSONObject("cards")), lockedAt4.cards)
                        assertEquals("$id lock@4 count", wq.getInt("hand_count"), lockedAt4.handCount)
                    }
                    if (i == frames.length() - 1) {
                        val lockedAtEnd = autoLock.lock()
                        if (want.isNull("auto_lock_qualified")) {
                            assertEquals("$id final lock 应为 null", null, lockedAtEnd)
                        } else {
                            val wq = want.getJSONObject("auto_lock_qualified")
                            assertEquals("$id final lock cards", jsonCounts(wq.getJSONObject("cards")), lockedAtEnd!!.cards)
                            assertEquals("$id final lock count", wq.getInt("hand_count"), lockedAtEnd.handCount)
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val SIGNATURE_COLS = 12
        const val SIGNATURE_ROWS = 8
        const val STABLE_CHANGE_PERCENT = 1.5
    }
}
