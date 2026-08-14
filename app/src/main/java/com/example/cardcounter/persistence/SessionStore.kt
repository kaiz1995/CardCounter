package com.example.cardcounter.persistence

import android.content.Context
import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.PauseReason
import com.example.cardcounter.domain.OutsideSeat
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import org.json.JSONArray
import org.json.JSONObject

/** 本地事件日志。首版不联网、不保存完整屏幕帧。 */
class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** 首次进入正向累计版时，替换旧事件日志并在同一次持久化中写入迁移标记。 */
    fun migratePositiveCountSessionIfNeeded(): GameState? = synchronized(MIGRATION_LOCK) {
        val bootstrap = positiveCountSessionBootstrap(
            preferences.getBoolean(KEY_POSITIVE_COUNT_MIGRATION, false),
            load()
        )
        if (!bootstrap.migrated) return@synchronized null
        check(
            preferences.edit()
                .putString(KEY_EVENTS, encodeEvents(bootstrap.state))
                .putBoolean(KEY_POSITIVE_COUNT_MIGRATION, true)
                .commit()
        ) { "无法保存正向累计会话迁移" }
        bootstrap.state
    }

    fun load(): GameState? {
        val raw = preferences.getString(KEY_EVENTS, null) ?: return null
        return runCatching {
            val array = JSONArray(raw)
            val events = buildList {
                for (index in 0 until array.length()) {
                    add(decodeEvent(array.getJSONObject(index)))
                }
            }
            GameReducer.replay(events.withoutRecoverablePause())
        }.getOrNull()
    }

    fun save(state: GameState) {
        preferences.edit().putString(KEY_EVENTS, encodeEvents(state)).apply()
    }

    private fun encodeEvents(state: GameState): String = JSONArray().also { array ->
        state.events.forEach { array.put(encodeEvent(it)) }
    }.toString()

    private fun encodeEvent(event: GameEvent): JSONObject = JSONObject()
        .put("id", event.id)
        .put("type", event.javaClass.simpleName)
        .also { json ->
            when (event) {
                is GameEvent.SelfHandLocked -> json.put("cards", encodeCounts(event.cards))
                is GameEvent.OutsideCardsPlayed -> {
                    json.put("cards", encodeCounts(event.cards))
                    event.seat?.let { json.put("seat", it.name) }
                    json.put("cardCount", event.cardCount)
                }
                is GameEvent.SelfCardsPlayed -> json.put("cards", encodeCounts(event.cards))
                is GameEvent.Reverted -> json.put("target", event.targetEventId)
                is GameEvent.SessionPaused -> json.put("reason", event.reason.name)
                else -> Unit
            }
        }

    private fun decodeEvent(json: JSONObject): GameEvent {
        val id = json.getString("id")
        return when (json.getString("type")) {
            "NewSession" -> GameEvent.NewSession(id)
            "SelfHandLocked" -> GameEvent.SelfHandLocked(decodeCounts(json.getJSONObject("cards")), id)
            "OutsideCardsPlayed" -> GameEvent.OutsideCardsPlayed(
                decodeCounts(json.getJSONObject("cards")),
                id,
                json.optString("seat").takeIf { it.isNotEmpty() }
                    ?.let { OutsideSeat.valueOf(it) },
                json.optInt("cardCount", decodeCounts(json.getJSONObject("cards")).total())
            )
            "SelfCardsPlayed" -> GameEvent.SelfCardsPlayed(decodeCounts(json.getJSONObject("cards")), id)
            "Reverted" -> GameEvent.Reverted(json.getString("target"), id)
            "SessionPaused" -> GameEvent.SessionPaused(
                PauseReason.valueOf(json.getString("reason")), id
            )
            "SessionClosed" -> GameEvent.SessionClosed(id)
            else -> error("未知事件")
        }
    }

    private fun encodeCounts(counts: RankCounts): JSONObject = JSONObject().also { json ->
        Rank.entries.forEach { rank -> json.put(rank.name, counts[rank]) }
    }

    private fun decodeCounts(json: JSONObject): RankCounts = RankCounts.of(
        Rank.entries.associateWith { rank -> json.optInt(rank.name, 0) }
    )

    private companion object {
        const val PREFERENCES = "card_counter_session"
        const val KEY_EVENTS = "events"
        const val KEY_POSITIVE_COUNT_MIGRATION = "positive_count_session_migration_v1"
        val MIGRATION_LOCK = Any()
    }
}

internal data class PositiveCountSessionBootstrap(val state: GameState, val migrated: Boolean)

internal fun positiveCountSessionBootstrap(
    markerPresent: Boolean,
    stored: GameState?
): PositiveCountSessionBootstrap = if (markerPresent) {
    PositiveCountSessionBootstrap(stored ?: GameReducer.newSession(), migrated = false)
} else {
    PositiveCountSessionBootstrap(GameReducer.newSession(), migrated = true)
}

internal fun List<GameEvent>.withoutRecoverablePause(): List<GameEvent> =
    if (lastOrNull() is GameEvent.SessionPaused &&
        (last() as GameEvent.SessionPaused).reason in setOf(
            PauseReason.RANK_UNSTABLE,
            PauseReason.BASELINE_FAILURE
        )
    ) dropLast(1) else this
