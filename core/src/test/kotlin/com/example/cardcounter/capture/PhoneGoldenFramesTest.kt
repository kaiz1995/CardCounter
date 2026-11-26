package com.example.cardcounter.capture

import com.example.cardcounter.vision.ArgbRaster
import com.example.cardcounter.vision.Raster
import javax.imageio.ImageIO
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一致性守卫：离线观察生成器（tools/dataset/phone_vision.py）与手机端 Kotlin 必须在同一批真实帧上零差异。
 *
 * 资源 core/src/test/resources/phone_golden/ 由 tools/dataset/make_phone_golden.py 生成：
 * 20 帧录屏原始帧（只留判定要读的区域，其余涂黑）和 Python 算出的 expected.json。
 * 这里用手机同一套函数重算，逐项比对：牌桌可见、各区三态、圆标、长串扩展后的裁剪框、指纹、静止。
 * 检测框部分不在这里比，见 RankModelConsistencyTest。
 *
 * 差异时本测试会列出每一项的帧号、区、字段、期望值和实际值。要改手机判定，就同时改 phone_vision.py 并重跑生成脚本。
 */
class PhoneGoldenFramesTest {
    private val expected: JSONObject by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/phone_golden/expected.json")) { "缺少 expected.json" }
        JSONObject(stream.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    private fun loadRaster(id: String): Raster {
        val stream = requireNotNull(javaClass.getResourceAsStream("/phone_golden/$id.png")) { "缺少帧 $id" }
        val image = stream.use { ImageIO.read(it) }
        assertEquals("$id 宽", ScreenCanvas.WIDTH, image.width)
        assertEquals("$id 高", ScreenCanvas.HEIGHT, image.height)
        val pixels = IntArray(image.width * image.height) { image.getRGB(it % image.width, it / image.width) and 0xffffff }
        return ArgbRaster(pixels, image.width, image.height)
    }

    /**
     * 与手机 CaptureProbeService.isCardWhiteColumn 同一规则：一列里三通道都 >= 190 的点至少 8 个。
     * 手机那份是私有函数，:core 够不到，这里按同样的常量重写；手机改了常量，这里也要跟着改。
     */
    private fun isCardWhiteColumn(raster: Raster, x: Int, top: Int, bottom: Int): Boolean {
        var white = 0
        for (y in top until bottom) {
            val rgb = raster.rgb(x, y)
            val red = (rgb shr 16) and 0xff
            val green = (rgb shr 8) and 0xff
            val blue = rgb and 0xff
            if (red >= PLAY_WHITE_LIMIT && green >= PLAY_WHITE_LIMIT && blue >= PLAY_WHITE_LIMIT) {
                white++
                if (white >= CARD_WHITE_COLUMN_MIN_PIXELS) return true
            }
        }
        return false
    }

    /** 64 位 FNV-1a，逐点 0xRRGGBB；与 phone_vision.fingerprint_hash 同一公式（Long 乘法按 2^64 回绕）。 */
    private fun fingerprintHash(fingerprint: IntArray): String {
        var hash = 0xCBF29CE484222325uL.toLong()
        for (value in fingerprint) {
            hash = (hash xor (value.toLong() and 0xffffffL)) * 0x100000001B3L
        }
        return java.lang.Long.toHexString(hash).padStart(16, '0')
    }

    @Test
    fun kotlinMatchesPythonOnEveryGoldenFrame() {
        val frames = expected.getJSONArray("frames")
        assertEquals("守卫要 20 帧", 20, frames.length())

        val diffs = mutableListOf<String>()
        val fingerprints = HashMap<String, Map<PlayZone, IntArray>>()
        var turnsLit = 0
        var cropsExpanded = 0
        val states = HashSet<String>()
        var hiddenTables = 0
        var stillChecks = 0

        for (index in 0 until frames.length()) {
            val frame = frames.getJSONObject(index)
            val id = frame.getString("id")
            val raster = loadRaster(id)

            val tableVisible = isTableVisible(raster)
            if (tableVisible != frame.getBoolean("table_visible")) {
                diffs += "$id table_visible 期望 ${frame.getBoolean("table_visible")} 实际 $tableVisible"
            }
            if (!tableVisible) hiddenTables++

            val states3 = playZoneStates(raster)
            val zonesJson = frame.getJSONObject("zones")
            val ownFingerprints = HashMap<PlayZone, IntArray>()
            for (name in zonesJson.keys()) {
                val zone = PlayZone.valueOf(name)
                val want = zonesJson.getJSONObject(name)

                val state = states3.getValue(zone).name
                if (state != want.getString("state")) diffs += "$id $name state 期望 ${want.getString("state")} 实际 $state"
                states += state

                val turn = TurnBadge.isShown(zone) { x, y -> raster.rgb(x, y) }
                if (turn != want.getBoolean("turn")) diffs += "$id $name turn 期望 ${want.getBoolean("turn")} 实际 $turn"
                if (turn) turnsLit++

                val crop = zone.expandedYoloCrop(ScreenCanvas.WIDTH, ScreenCanvas.HEIGHT) { x, top, bottom ->
                    isCardWhiteColumn(raster, x, top, bottom)
                }
                val cropList = listOf(crop.left, crop.top, crop.width, crop.height)
                val wantCrop = want.getJSONArray("crop").let { array -> List(array.length()) { array.getInt(it) } }
                if (cropList != wantCrop) diffs += "$id $name crop 期望 $wantCrop 实际 $cropList"
                val base = zone.yoloCrop(ScreenCanvas.WIDTH, ScreenCanvas.HEIGHT)
                if (crop != base) cropsExpanded++

                val fingerprint = RoiFingerprint.of(zone) { x, y -> raster.rgb(x, y) }
                ownFingerprints[zone] = fingerprint
                val hash = fingerprintHash(fingerprint)
                if (hash != want.getString("fp")) diffs += "$id $name fp 期望 ${want.getString("fp")} 实际 $hash"

                if (want.has("still")) {
                    val previous = fingerprints[frame.getString("prev")]?.get(zone)
                    val still = previous != null && RoiFingerprint.isStill(previous, fingerprint)
                    if (previous == null) diffs += "$id $name 缺上一帧 ${frame.optString("prev")}"
                    if (still != want.getBoolean("still")) diffs += "$id $name still 期望 ${want.getBoolean("still")} 实际 $still"
                    stillChecks++
                }
            }
            fingerprints[id] = ownFingerprints
        }

        assertTrue("手机与离线观察有 ${diffs.size} 项差异：\n" + diffs.joinToString("\n"), diffs.isEmpty())

        // 守卫自己的覆盖：帧里必须真有这些场景，否则“零差异”什么也没证明。
        assertEquals("三态要齐", setOf("CARD", "EMPTY", "OCCLUDED"), states)
        assertTrue("圆标亮的区次不足：$turnsLit", turnsLit >= 5)
        assertTrue("长串扩展的区次不足：$cropsExpanded", cropsExpanded >= 4)
        assertTrue("要有牌桌被挡的帧", hiddenTables >= 1)
        assertTrue("要有静止对比", stillChecks >= 5)
    }

    private object ScreenCanvas {
        const val WIDTH = 1_280
        const val HEIGHT = 578
    }

    private companion object {
        // 与 CaptureProbeService 里的私有常量一致。
        const val PLAY_WHITE_LIMIT = 190
        const val CARD_WHITE_COLUMN_MIN_PIXELS = 8
    }
}
