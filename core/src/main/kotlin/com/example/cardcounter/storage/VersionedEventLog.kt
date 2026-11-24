package com.example.cardcounter.storage

import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.OutsideSeat
import com.example.cardcounter.domain.PauseReason
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCompleteness
import com.example.cardcounter.domain.RankCounts
import org.json.JSONArray
import org.json.JSONObject

/** 版本化 CAS 保存结果。 */
sealed interface SaveResult {
    /** CAS 通过；json 为应写入磁盘的版本化快照（schema=2，seq=事件数）。 */
    data class Ok(val json: String) : SaveResult

    /** 磁盘序列号大于期望值：旧版本写入被拒绝，禁止覆盖新版本。 */
    data object StaleRejected : SaveResult
}

/**
 * 纯 Kotlin 的事件日志序列化与 CAS 存储（无 android 依赖，JVM 可单测）。
 *
 * 版本化格式：{"schema":2,"seq":<events.size>,"events":[...]}
 * - seq 即事件数，作为乐观锁期望值；
 * - 旧格式（裸事件数组，无 schema/seq 包装）decode 返回 null，由调用方按 seq=0 兼容；
 * - 空磁盘视为 seq=0，可写入。
 */
object VersionedEventLog {
    const val SCHEMA = 2

    /** 序列化完整状态；seq 取当前事件数。 */
    fun encode(state: GameState): String {
        val array = JSONArray()
        state.events.forEach { array.put(encodeEvent(it)) }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("seq", state.events.size)
            .put("events", array)
            .toString()
    }

    /** 反序列化：返回 (seq, events)；损坏或非版本化格式返回 null。 */
    fun decode(json: String): Pair<Int, List<GameEvent>>? = runCatching {
        val obj = JSONObject(json)
        if (obj.optInt("schema", -1) != SCHEMA) return@runCatching null
        if (!obj.has("seq") || !obj.has("events")) return@runCatching null
        val array = obj.getJSONArray("events")
        val events = buildList {
            for (index in 0 until array.length()) {
                add(decodeEvent(array.getJSONObject(index)))
            }
        }
        obj.getInt("seq") to events
    }.getOrNull()

    /**
     * CAS 保存：仅当磁盘当前 seq 不大于 expectedSeq 时允许写入。
     * 空磁盘、旧格式或损坏内容按 seq=0 处理（这是损坏日志的覆盖恢复路径）。
     */
    fun saveIfCurrent(currentJson: String?, expectedSeq: Int, state: GameState): SaveResult {
        val currentSeq = currentJson?.let { decode(it)?.first } ?: 0
        if (currentSeq > expectedSeq) return SaveResult.StaleRejected
        return SaveResult.Ok(encode(state))
    }

    /** 事件 JSON 编码；缺省字段兼容旧数据。 */
    fun encodeEvent(event: GameEvent): JSONObject = JSONObject()
        .put("id", event.id)
        .put("type", event.javaClass.simpleName)
        .also { json ->
            when (event) {
                is GameEvent.SelfHandLocked -> json.put("cards", encodeCounts(event.cards))
                    .put("cardCount", event.cardCount)
                    .put("rankCompleteness", event.rankCompleteness.name)
                is GameEvent.OutsideCardsPlayed -> {
                    json.put("cards", encodeCounts(event.cards))
                    json.put("cardCount", event.cardCount)
                    json.put("rankCompleteness", event.rankCompleteness.name)
                    event.seat?.let { json.put("seat", it.name) }
                }
                is GameEvent.SelfCardsPlayed -> json.put("cards", encodeCounts(event.cards))
                    .put("cardCount", event.cardCount)
                    .put("rankCompleteness", event.rankCompleteness.name)
                is GameEvent.Reverted -> json.put("target", event.targetEventId)
                is GameEvent.SessionPaused -> json.put("reason", event.reason.name)
                // NewSession / SessionClosed / SessionResumed 只需 id + type。
                else -> Unit
            }
        }

    /** 事件 JSON 解码；缺省 rankCompleteness 视为 COMPLETE，兼容旧数据。 */
    fun decodeEvent(json: JSONObject): GameEvent {
        val id = json.getString("id")
        return when (json.getString("type")) {
            "NewSession" -> GameEvent.NewSession(id)
            "SelfHandLocked" -> GameEvent.SelfHandLocked(
                decodeCounts(json.getJSONObject("cards")), id,
                json.optInt("cardCount", decodeCounts(json.getJSONObject("cards")).total()),
                decodeCompleteness(json)
            )
            "OutsideCardsPlayed" -> GameEvent.OutsideCardsPlayed(
                decodeCounts(json.getJSONObject("cards")),
                id,
                json.optString("seat").takeIf { it.isNotEmpty() }
                    ?.let { OutsideSeat.valueOf(it) },
                json.optInt("cardCount", decodeCounts(json.getJSONObject("cards")).total()),
                decodeCompleteness(json)
            )
            "SelfCardsPlayed" -> GameEvent.SelfCardsPlayed(
                decodeCounts(json.getJSONObject("cards")), id,
                json.optInt("cardCount", decodeCounts(json.getJSONObject("cards")).total()),
                decodeCompleteness(json)
            )
            "Reverted" -> GameEvent.Reverted(json.getString("target"), id)
            "SessionPaused" -> GameEvent.SessionPaused(
                PauseReason.valueOf(json.getString("reason")), id
            )
            "SessionResumed" -> GameEvent.SessionResumed(id)
            "SessionClosed" -> GameEvent.SessionClosed(id)
            else -> error("未知事件")
        }
    }

    private fun decodeCompleteness(json: JSONObject): RankCompleteness =
        runCatching { RankCompleteness.valueOf(json.optString("rankCompleteness")) }
            .getOrDefault(RankCompleteness.COMPLETE)

    private fun encodeCounts(counts: RankCounts): JSONObject = JSONObject().also { json ->
        Rank.entries.forEach { rank -> json.put(rank.name, counts[rank]) }
    }

    private fun decodeCounts(json: JSONObject): RankCounts = RankCounts.of(
        Rank.entries.associateWith { rank -> json.optInt(rank.name, 0) }
    )
}
