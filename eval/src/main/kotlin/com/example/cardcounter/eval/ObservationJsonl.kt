package com.example.cardcounter.eval

import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.recognition.Detection
import com.example.cardcounter.recognition.FrameObservation
import com.example.cardcounter.recognition.ZoneObservation
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 逐帧观察 JSONL：每行一个 FrameObservation，格式见 DATA-FORMATS 第 5 节。
 * 行间不保证按时间排序，回放前由调用方排序。
 */
object ObservationJsonl {
    fun parse(text: String, source: String = "obs.jsonl"): List<FrameObservation> {
        val errors = mutableListOf<String>()
        val frames = mutableListOf<FrameObservation>()
        val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for ((index, raw) in lines.withIndex()) {
            if (raw.isBlank()) continue
            val line = index + 1
            val frame = runCatching { parseLine(raw) }
                .getOrElse { error ->
                    errors += "第 $line 行: ${error.message ?: error::class.java.simpleName}"
                    null
                }
            frame?.let { frames += it }
        }
        if (errors.isNotEmpty()) fail(errors, source)
        return frames
    }

    fun parseLine(raw: String): FrameObservation {
        val json = try {
            JSONObject(raw)
        } catch (error: JSONException) {
            throw IllegalArgumentException("不是合法 JSON: ${error.message}")
        }
        val timestamp = requireLong(json, "ts_ms")
        val tableVisible = requireBoolean(json, "table_visible")
        val zonesJson = json.optJSONObject("zones")
        val zones = mutableMapOf<PlayZone, ZoneObservation>()
        if (zonesJson != null) {
            for (key in zonesJson.keySet()) {
                val zone = PlayZone.entries.firstOrNull { it.name == key }
                    ?: throw IllegalArgumentException(
                        "未知出牌区 '$key'，允许 ${PlayZone.entries.joinToString("|") { it.name }}"
                    )
                zones[zone] = parseZone(zone, zonesJson.getJSONObject(key))
            }
        }
        return FrameObservation(timestampMs = timestamp, tableVisible = tableVisible, zones = zones)
    }

    private fun parseZone(zone: PlayZone, json: JSONObject): ZoneObservation {
        val cardVisible = requireBoolean(json, "card_visible")
        val still = if (json.has("still")) requireBoolean(json, "still") else false
        if (!json.has("dets") || json.isNull("dets")) {
            return ZoneObservation(zone, cardVisible, still, detections = null)
        }
        val dets = json.get("dets")
        if (dets !is JSONArray) throw IllegalArgumentException("${zone.name}.dets 不是数组")
        val parsed = (0 until dets.length()).map { index ->
            val item = dets.get(index)
            if (item !is JSONObject) throw IllegalArgumentException("${zone.name}.dets[$index] 不是对象")
            parseDetection(zone, index, item)
        }
        return ZoneObservation(zone, cardVisible, still, parsed)
    }

    private fun parseDetection(zone: PlayZone, index: Int, json: JSONObject): Detection {
        val prefix = "${zone.name}.dets[$index]"
        val rankText = json.optString("rank", "")
        val rank = Rank.entries.firstOrNull { it.name == rankText }
            ?: throw IllegalArgumentException("$prefix.rank 非法 '$rankText'")
        val confidence = requireDouble(json, "conf").let {
            requireRange(it, 0.0, 1.0, "$prefix.conf")
            it.toFloat()
        }
        val cx = requireUnitDouble(json, "cx", prefix)
        val cy = requireUnitDouble(json, "cy", prefix)
        val w = requireUnitDouble(json, "w", prefix)
        val h = requireUnitDouble(json, "h", prefix)
        return Detection(rank, confidence, cx.toFloat(), cy.toFloat(), w.toFloat(), h.toFloat())
    }

    private fun requireUnitDouble(json: JSONObject, key: String, prefix: String): Double {
        val value = requireDouble(json, key)
        requireRange(value, 0.0, 1.0, "$prefix.$key")
        return value
    }

    private fun requireRange(value: Double, min: Double, max: Double, name: String) {
        if (value < min || value > max) {
            throw IllegalArgumentException("$name 超出 $min..$max: $value")
        }
    }

    private fun requireLong(json: JSONObject, key: String): Long {
        if (!json.has(key) || json.isNull(key)) throw IllegalArgumentException("缺少字段 $key")
        return json.getLong(key)
    }

    private fun requireDouble(json: JSONObject, key: String): Double {
        if (!json.has(key) || json.isNull(key)) throw IllegalArgumentException("缺少字段 $key")
        return json.getDouble(key)
    }

    private fun requireBoolean(json: JSONObject, key: String): Boolean {
        if (!json.has(key) || json.isNull(key)) throw IllegalArgumentException("缺少字段 $key")
        return json.getBoolean(key)
    }
}
