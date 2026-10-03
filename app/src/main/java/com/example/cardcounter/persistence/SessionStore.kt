package com.example.cardcounter.persistence

import android.content.Context
import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.PauseReason
import com.example.cardcounter.domain.OutsideSeat
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCompleteness
import com.example.cardcounter.domain.RankCounts
import org.json.JSONArray
import org.json.JSONObject

/** 本地事件日志。首版不联网、不保存完整屏幕帧。 */
class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): GameState? {
        val raw = preferences.getString(KEY_EVENTS, null) ?: return null
        return runCatching {
            val array = JSONArray(raw)
            val events = buildList {
                for (index in 0 until array.length()) {
                    add(EventJsonCodec.decodeEvent(array.getJSONObject(index)))
                }
            }
            // 重启不自动解除暂停：暂停状态按事件日志原样回放恢复。
            GameReducer.replay(events)
        }.getOrNull()
    }

    fun save(state: GameState) {
        val array = JSONArray()
        state.events.forEach { array.put(EventJsonCodec.encodeEvent(it)) }
        preferences.edit().putString(KEY_EVENTS, array.toString()).apply()
    }

    private companion object {
        const val PREFERENCES = "card_counter_session"
        const val KEY_EVENTS = "events"
    }
}

/** 事件 JSON 编解码；缺省 rankCompleteness 视为 COMPLETE，兼容旧数据。 */
internal object EventJsonCodec {
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
                else -> Unit
            }
        }

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
