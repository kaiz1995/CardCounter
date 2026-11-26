package com.example.cardcounter.storage

import com.example.cardcounter.domain.DispatchResult
import org.json.JSONArray
import org.json.JSONObject

/** 单条诊断观察记录：只记证据与决策元数据，不保存屏幕帧。 */
data class ObservationRecord(
    /** 证据 ID；与账本事件的 eventId 一一对应（观察阶段可为 null）。 */
    val evidenceId: String?,
    val zone: String,
    /** SELF / OUTSIDE。 */
    val source: String,
    /** 这一手的张数（仅诊断观察用，不是账本数据）；未知为 null。 */
    val quantity: Int?,
    /** Rank 向量总数；未知为 null。 */
    val rankTotal: Int?,
    /** 决策结果，词表见 [DiagnosticsLog.Decision]。 */
    val decision: String,
    /** 拒绝/暂停/重试原因；STABLE 记录里是这一手的交出原因（见 FinalizeReason），可空。 */
    val reason: String? = null,
    val capturedAtMillis: Long
)

/**
 * 纯 Kotlin 诊断日志核心（可注入时间源，JVM 可单测）。
 * 内存环形缓冲上限 200 条，超出淘汰最旧；
 * 持久化由 SessionStore 侧独立 SharedPreferences key 承担，轮换保留最近 200 条。
 */
class DiagnosticsLog(
    private val clock: () -> Long = System::currentTimeMillis,
    private val capacity: Int = DEFAULT_CAPACITY
) {
    /** decision 词表固定；记牌只接受点数完整的出牌，没有“只记张数”这一档。 */
    object Decision {
        const val OBSERVED = "OBSERVED"
        const val STABLE = "STABLE"
        const val ACCEPTED = "ACCEPTED"
        const val REJECTED = "REJECTED"
        const val RETRY = "RETRY"
        const val PAUSED = "PAUSED"
        const val DUPLICATE = "DUPLICATE"

        /** 派发结果 → 决策词表。 */
        fun forResult(result: DispatchResult): String = when (result) {
            is DispatchResult.Accepted -> ACCEPTED
            is DispatchResult.DuplicateIgnored -> DUPLICATE
            is DispatchResult.Rejected -> REJECTED
            is DispatchResult.Paused -> PAUSED
        }
    }

    private val records = ArrayDeque<ObservationRecord>()

    /** 记录一条诊断并按容量轮换淘汰最旧记录。 */
    @Synchronized
    fun record(record: ObservationRecord) {
        records.addLast(record)
        while (records.size > capacity) {
            records.removeFirst()
        }
    }

    /** 便捷记录：时间戳由注入的时间源生成。 */
    @Synchronized
    fun record(
        evidenceId: String?,
        zone: String,
        source: String,
        quantity: Int?,
        rankTotal: Int?,
        decision: String,
        reason: String? = null
    ): ObservationRecord =
        ObservationRecord(evidenceId, zone, source, quantity, rankTotal, decision, reason, clock())
            .also { record(it) }

    /** 当前缓冲快照（最旧在前）。 */
    @Synchronized
    fun snapshot(): List<ObservationRecord> = records.toList()

    @Synchronized
    fun size(): Int = records.size

    /** 序列化最近记录，交由 SessionStore 侧持久化。 */
    @Synchronized
    fun toRecentJson(): String {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        return JSONObject()
            .put("schema", 1)
            .put("records", array)
            .toString()
    }

    /** 从持久化 JSON 恢复；损坏返回 false，超出容量的旧记录轮换丢弃。 */
    @Synchronized
    fun loadRecentJson(json: String): Boolean = runCatching {
        val array = JSONObject(json).getJSONArray("records")
        records.clear()
        for (index in 0 until array.length()) {
            records.addLast(decodeRecord(array.getJSONObject(index)))
        }
        while (records.size > capacity) {
            records.removeFirst()
        }
        true
    }.getOrDefault(false)

    private fun ObservationRecord.toJson(): JSONObject = JSONObject().also { json ->
        evidenceId?.let { json.put("evidenceId", it) }
        json.put("zone", zone)
        json.put("source", source)
        quantity?.let { json.put("quantity", it) }
        rankTotal?.let { json.put("rankTotal", it) }
        json.put("decision", decision)
        reason?.let { json.put("reason", it) }
        json.put("capturedAtMillis", capturedAtMillis)
    }

    private fun decodeRecord(json: JSONObject): ObservationRecord = ObservationRecord(
        evidenceId = if (json.has("evidenceId")) json.getString("evidenceId") else null,
        zone = json.getString("zone"),
        source = json.getString("source"),
        quantity = if (json.has("quantity")) json.getInt("quantity") else null,
        rankTotal = if (json.has("rankTotal")) json.getInt("rankTotal") else null,
        decision = json.getString("decision"),
        reason = if (json.has("reason")) json.getString("reason") else null,
        capturedAtMillis = json.getLong("capturedAtMillis")
    )

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
