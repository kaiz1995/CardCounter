package com.example.cardcounter.storage

import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.OutsideSeat
import com.example.cardcounter.domain.PauseReason
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import org.json.JSONArray
import org.json.JSONObject

/** 版本化 CAS 保存结果。 */
sealed interface SaveResult {
    /** CAS 通过；json 为应写入磁盘的版本化快照（schema=3，seq=事件数）。 */
    data class Ok(val json: String) : SaveResult

    /** 磁盘序列号大于期望值：旧版本写入被拒绝，禁止覆盖新版本。 */
    data object StaleRejected : SaveResult
}

/**
 * 纯 Kotlin 的事件日志序列化与 CAS 存储（无 android 依赖，JVM 可单测）。
 *
 * 版本化格式：{"schema":3,"seq":<events.size>,"events":[...]}
 * - seq 即事件数，作为乐观锁期望值；
 * - 版本号不是 3 的数据（旧版本、裸事件数组、损坏内容）一律 decode 返回 null，
 *   由 [restoreOrNew] 丢弃并开新局，不做兼容迁移；
 * - 空磁盘视为 seq=0，可写入。
 */
object VersionedEventLog {
    const val SCHEMA = 3

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

    /** 反序列化：返回 (seq, events)；损坏或版本不符返回 null。 */
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
        val seq = strictInt(obj, "seq")
        // 结构不完整也当损坏：空日志、首条不是开局、seq 与事件数对不上。
        // 否则回放出来是损坏暂停、恢复又被拒，重启后会卡死，而不是丢弃开新局。
        if (events.isEmpty() || events.first() !is GameEvent.NewSession || seq != events.size) {
            return@runCatching null
        }
        seq to events
    }.getOrNull()

    /**
     * 从磁盘内容恢复会话：当前版本按事件日志回放；旧版本、损坏内容直接丢弃，返回全新会话和 seq=0。
     * 重启不自动解除暂停，暂停状态按事件日志原样恢复。
     */
    fun restoreOrNew(json: String?): Pair<GameState, Int> {
        val decoded = json?.let(::decode)
        return if (decoded == null) {
            GameReducer.newSession() to 0
        } else {
            GameReducer.replay(decoded.second) to decoded.first
        }
    }

    /**
     * CAS 保存：仅当磁盘当前 seq 不大于 expectedSeq 时允许写入。
     * 空磁盘、旧版本或损坏内容按 seq=0 处理（这是旧数据与损坏日志的覆盖恢复路径）。
     */
    fun saveIfCurrent(currentJson: String?, expectedSeq: Int, state: GameState): SaveResult {
        val currentSeq = currentJson?.let { decode(it)?.first } ?: 0
        if (currentSeq > expectedSeq) return SaveResult.StaleRejected
        return SaveResult.Ok(encode(state))
    }

    /** 事件 JSON 编码。 */
    fun encodeEvent(event: GameEvent): JSONObject = JSONObject()
        .put("id", event.id)
        .put("type", event.javaClass.simpleName)
        .also { json ->
            when (event) {
                // handCount 没数出来时显式写 JSON null（org.json 的 put(key, null) 会删键，读回来会当缺键判损坏）。
                is GameEvent.HandConfirmed -> json.put("cards", encodeCounts(event.cards))
                    .put("handCount", event.handCount ?: JSONObject.NULL)
                is GameEvent.OutsideCardsPlayed -> json.put("cards", encodeCounts(event.cards))
                    .put("seat", event.seat.name)
                is GameEvent.Reverted -> json.put("target", event.targetEventId)
                is GameEvent.SessionPaused -> json.put("reason", event.reason.name)
                // NewSession / SessionClosed / SessionResumed 只需 id + type。
                is GameEvent.NewSession, is GameEvent.SessionClosed, is GameEvent.SessionResumed -> Unit
            }
        }

    /** 事件 JSON 解码；缺字段或未知类型抛异常，由 [decode] 整体判为不可用。 */
    fun decodeEvent(json: JSONObject): GameEvent {
        val id = json.getString("id")
        return when (json.getString("type")) {
            "NewSession" -> GameEvent.NewSession(id)
            "HandConfirmed" -> GameEvent.HandConfirmed(
                decodeCounts(json.getJSONObject("cards")), strictIntOrNull(json, "handCount"), id
            )
            "OutsideCardsPlayed" -> GameEvent.OutsideCardsPlayed(
                decodeCounts(json.getJSONObject("cards")),
                OutsideSeat.valueOf(json.getString("seat")),
                id
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

    private fun encodeCounts(counts: RankCounts): JSONObject = JSONObject().also { json ->
        Rank.entries.forEach { rank -> json.put(rank.name, counts[rank]) }
    }

    /**
     * 点数向量必须 15 个点数一个不少、一个不多，每个都是非负整数（负数由 [RankCounts.of] 拒绝）。
     * 缺键、null、字符串、小数都抛异常，由 [decode] 整份判为损坏，不能悄悄当成 0 张。
     */
    private fun decodeCounts(json: JSONObject): RankCounts {
        require(json.length() == Rank.entries.size) { "点数向量字段数不对: ${json.length()}" }
        return RankCounts.of(Rank.entries.associateWith { rank -> strictInt(json, rank.name) })
    }

    /** 只认 JSON 整数；缺键、null、字符串、小数、布尔都抛异常。 */
    private fun strictInt(json: JSONObject, key: String): Int {
        val value = json.get(key)
        require(value is Int) { "字段 $key 不是整数: $value" }
        return value
    }

    /** 键必须在；值只认 JSON 整数或显式 null，缺键、字符串、小数、布尔都抛异常。 */
    private fun strictIntOrNull(json: JSONObject, key: String): Int? {
        val value = json.get(key)
        if (value == JSONObject.NULL) return null
        require(value is Int) { "字段 $key 不是整数或 null: $value" }
        return value
    }
}
