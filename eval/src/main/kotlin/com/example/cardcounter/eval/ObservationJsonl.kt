package com.example.cardcounter.eval

import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.capture.RoiFingerprint
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.recognition.Detection
import com.example.cardcounter.recognition.FrameObservation
import com.example.cardcounter.recognition.ZoneObservation
import com.example.cardcounter.vision.RankModelContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * 逐帧观察 JSONL：首行必须是元数据对象 `{"_meta":true,"vision_rule_version":"...","model_sha256":"..."}`，
 * 之后每行一个 FrameObservation，格式见 DATA-FORMATS 第 5 节。
 * 行间不保证按时间排序，回放前由调用方排序。
 * 字段类型严格校验：不接受字符串布尔、浮点时间戳、NaN，也不接受一行里拼两个对象；
 * 视觉规则版本或模型 SHA-256 缺失、与 [RankModelContract] 不一致时直接报错退出。
 */
object ObservationJsonl {
    const val META_KEY = "_meta"
    const val VERSION_KEY = "vision_rule_version"
    const val MODEL_SHA256_KEY = "model_sha256"
    const val VERSION_MISMATCH_MESSAGE = "视觉规则或模型已改，请用 onnx_detect.py 全量重新生成 dev+tuning 观察"

    fun metaLine(
        version: String = RankModelContract.VISION_RULE_VERSION,
        modelSha256: String = RankModelContract.MODEL_SHA256
    ): String =
        """{"$META_KEY":true,"$VERSION_KEY":"$version","$MODEL_SHA256_KEY":"$modelSha256"}"""

    fun parse(text: String, source: String = "obs.jsonl"): List<FrameObservation> {
        val errors = mutableListOf<String>()
        val frames = mutableListOf<FrameObservation>()
        val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var metaChecked = false
        for ((index, raw) in lines.withIndex()) {
            if (raw.isBlank()) continue
            val line = index + 1
            if (!metaChecked) {
                metaChecked = true
                val (metaVersion, metaModelSha) = readMeta(raw)
                if (metaVersion != RankModelContract.VISION_RULE_VERSION || metaModelSha != RankModelContract.MODEL_SHA256) {
                    throw DataFormatException(
                        "$source: $VERSION_MISMATCH_MESSAGE（观察版本=${metaVersion ?: "缺失"}, 模型=${metaModelSha ?: "缺失"}；" +
                            "当前代码版本=${RankModelContract.VISION_RULE_VERSION}, 模型=${RankModelContract.MODEL_SHA256}）"
                    )
                }
                continue
            }
            val frame = runCatching { parseLine(raw) }
                .getOrElse { error ->
                    errors += "第 $line 行: ${error.message ?: error::class.java.simpleName}"
                    null
                }
            frame?.let { frames += it }
        }
        if (!metaChecked) {
            throw DataFormatException(
                "$source: $VERSION_MISMATCH_MESSAGE（观察版本=缺失, 模型=缺失；" +
                    "当前代码版本=${RankModelContract.VISION_RULE_VERSION}, 模型=${RankModelContract.MODEL_SHA256}）"
            )
        }
        if (errors.isNotEmpty()) fail(errors, source)
        return frames
    }

    private fun readMeta(raw: String): Pair<String?, String?> {
        val json = runCatching { JsonFields.parseObject(raw) }.getOrNull() ?: return null to null
        if (json.opt(META_KEY) != true) return null to null
        val version = runCatching { JsonFields.string(json, VERSION_KEY) }.getOrNull()
        val modelSha = runCatching { JsonFields.string(json, MODEL_SHA256_KEY) }.getOrNull()
        return version to modelSha
    }

    fun parseLine(raw: String): FrameObservation {
        val json = JsonFields.parseObject(raw)
        val timestamp = JsonFields.long(json, "ts_ms")
        if (timestamp < 0) throw IllegalArgumentException("ts_ms 不能为负: $timestamp")
        val tableVisible = JsonFields.boolean(json, "table_visible")
        val zonesJson = JsonFields.obj(json, "zones")
        val zones = mutableMapOf<PlayZone, ZoneObservation>()
        for (key in zonesJson.keySet()) {
            val zone = PlayZone.entries.firstOrNull { it.name == key }
                ?: throw IllegalArgumentException(
                    "未知出牌区 '$key'，允许 ${PlayZone.entries.joinToString("|") { it.name }}"
                )
            zones[zone] = parseZone(zone, JsonFields.obj(zonesJson, key, "zones.$key"))
        }
        return FrameObservation(timestampMs = timestamp, tableVisible = tableVisible, zones = zones)
    }

    private fun parseZone(zone: PlayZone, json: JSONObject): ZoneObservation {
        val prefix = zone.name
        val cardVisible = JsonFields.boolean(json, "card_visible", "$prefix.card_visible")
        val still = JsonFields.boolean(json, "still", "$prefix.still")
        val turn = if (JsonFields.has(json, "turn")) JsonFields.boolean(json, "turn", "$prefix.turn") else false
        val occluded = if (JsonFields.has(json, "occluded")) JsonFields.boolean(json, "occluded", "$prefix.occluded") else false
        if (occluded && cardVisible) throw IllegalArgumentException("$prefix.occluded 为 true 时 card_visible 必须为 false")
        val fingerprint = if (JsonFields.has(json, "fp")) parseFingerprint(json, prefix) else null
        if (!JsonFields.has(json, "dets")) {
            return ZoneObservation(
                zone, cardVisible, still, detections = null, turnBadge = turn, occluded = occluded, fingerprint = fingerprint
            )
        }
        val dets = json.get("dets")
        if (dets !is JSONArray) throw IllegalArgumentException("$prefix.dets 不是数组")
        val parsed = (0 until dets.length()).map { index ->
            val item = dets.get(index)
            if (item !is JSONObject) throw IllegalArgumentException("$prefix.dets[$index] 不是对象")
            parseDetection(zone, index, item)
        }
        return ZoneObservation(
            zone, cardVisible, still, parsed, turnBadge = turn, occluded = occluded, fingerprint = fingerprint
        )
    }

    private fun parseFingerprint(json: JSONObject, prefix: String): IntArray {
        val encoded = JsonFields.string(json, "fp", "$prefix.fp")
        val raw = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("$prefix.fp Base64 无效: ${error.message}")
        }
        val expectedPixels = RoiFingerprint.COLUMNS * RoiFingerprint.ROWS
        if (raw.size != expectedPixels * 3) {
            throw IllegalArgumentException("$prefix.fp 字节数 ${raw.size}，期望 ${expectedPixels * 3}")
        }
        return IntArray(expectedPixels) { index ->
            val offset = index * 3
            val r = raw[offset].toInt() and 0xff
            val g = raw[offset + 1].toInt() and 0xff
            val b = raw[offset + 2].toInt() and 0xff
            (r shl 16) or (g shl 8) or b
        }
    }

    private fun parseDetection(zone: PlayZone, index: Int, json: JSONObject): Detection {
        val prefix = "${zone.name}.dets[$index]"
        val rankText = JsonFields.string(json, "rank", "$prefix.rank")
        val rank = Rank.entries.firstOrNull { it.name == rankText }
            ?: throw IllegalArgumentException("$prefix.rank 非法 '$rankText'")
        val confidence = unit(json, "conf", prefix)
        val cx = unit(json, "cx", prefix)
        val cy = unit(json, "cy", prefix)
        val w = unit(json, "w", prefix)
        val h = unit(json, "h", prefix)
        return Detection(
            rank, confidence.toFloat(), cx.toFloat(), cy.toFloat(), w.toFloat(), h.toFloat()
        )
    }

    /** 0..1 的有限小数；NaN/Infinity 在 [JsonFields.double] 里已拒绝。 */
    private fun unit(json: JSONObject, key: String, prefix: String): Double {
        val value = JsonFields.double(json, key, "$prefix.$key")
        if (value < 0.0 || value > 1.0) {
            throw IllegalArgumentException("$prefix.$key 超出 0.0..1.0: $value")
        }
        return value
    }
}
