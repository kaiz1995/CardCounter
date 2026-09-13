package com.example.cardcounter.persistence

import android.content.Context
import android.os.SystemClock
import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.PauseReason
import com.example.cardcounter.domain.OutsideSeat
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地事件日志。首版不联网、不保存完整屏幕帧。
 *
 * 事件列表在内存里缓存（[cachedEvents]），[apply] 只做增量追加 + 内存态落盘，
 * 不再每次重读整份 JSON。写盘按 [WRITE_THROTTLE_MS] 节流，账本类事件则立即落盘。
 */
class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** 内存缓存的事件日志；null 表示尚未从磁盘读取。所有访问都在 [SESSION_LOCK] 内。 */
    private var cachedEvents: MutableList<GameEvent>? = null

    /** 最近一次写入磁盘的完整状态，供 [flush] 使用。 */
    private var lastState: GameState? = null

    /** 自 [lastWriteAt] 之后是否有事件只在内存、尚未落盘。 */
    private var dirty = false

    /** 上一次真正写盘的 [SystemClock.elapsedRealtime] 时间戳。 */
    private var lastWriteAt = 0L

    /**
     * 首次加载。若磁盘上还是「正向累计版」之前的事件日志，先整体替换成新会话，
     * 避免旧模型的事件被新归约器 replay 出错误的账本。
     *
     * 迁移必须走 [writeCommitted] 立即落盘：它与内存缓存的填充是同一次原子操作，
     * 不能让节流把「已迁移」这个事实延后，否则下一次冷启动会再迁一次。
     */
    fun loadOrMigrate(): GameState = synchronized(SESSION_LOCK) {
        val bootstrap = positiveCountSessionBootstrap(
            preferences.getBoolean(KEY_POSITIVE_COUNT_MIGRATION, false),
            loadUnlocked()?.let { GameReducer.replay(it.toList().withoutRecoverablePause()) }
        )
        cachedEvents = bootstrap.state.events.toMutableList()
        lastState = bootstrap.state
        if (bootstrap.migrated) {
            check(
                preferences.edit()
                    .putString(KEY_EVENTS, encodeEvents(bootstrap.state))
                    .putBoolean(KEY_POSITIVE_COUNT_MIGRATION, true)
                    .commit()
            ) { "无法保存会话迁移标记" }
            lastWriteAt = SystemClock.elapsedRealtime()
            dirty = false
        }
        bootstrap.state
    }

    private fun encodeEvents(state: GameState): String = JSONArray().also { array ->
        state.events.forEach { array.put(encodeEvent(it)) }
    }.toString()

    fun load(): GameState? = synchronized(SESSION_LOCK) {
        val events = cachedEvents ?: loadUnlocked() ?: return null
        GameReducer.replay(events.toList().withoutRecoverablePause())
    }

    /** 首次落盘（构造期用）。语义等同「写入缓存并立即持久化」。 */
    fun save(state: GameState) = synchronized(SESSION_LOCK) {
        cachedEvents = state.events.toMutableList()
        lastState = state
        writeCommitted(state)
    }

    /**
     * 追加一个事件。内存态已经由调用方（[com.example.cardcounter.persistence.SessionRepository]）
     * 通过 [GameReducer.apply] 算好，这里只负责把新事件并入缓存并按节流策略落盘。
     */
    fun apply(state: GameState, event: GameEvent) = synchronized(SESSION_LOCK) {
        val events = cachedEvents ?: loadUnlocked() ?: mutableListOf()
        events += event
        cachedEvents = events
        lastState = state
        val now = SystemClock.elapsedRealtime()
        if (shouldWriteImmediately(event, now, lastWriteAt)) {
            writeCommitted(state)
        } else {
            dirty = true
        }
    }

    /** 新局：丢弃旧事件日志，用新会话事件重建缓存并立即落盘。 */
    fun replaceWithNewSession(state: GameState) = synchronized(SESSION_LOCK) {
        cachedEvents = state.events.toMutableList()
        lastState = state
        writeCommitted(state)
    }

    /** 强制把内存里未落盘的改动写入磁盘。 */
    fun flush() = synchronized(SESSION_LOCK) {
        if (!dirty) return
        val state = lastState ?: return
        writeCommitted(state)
    }

    /** 同步落盘（[commit]），保证 [flush] 与 onDestroy 兜底可靠。 */
    private fun writeCommitted(state: GameState) {
        val array = JSONArray()
        state.events.forEach { array.put(encodeEvent(it)) }
        preferences.edit().putString(KEY_EVENTS, array.toString()).commit()
        lastWriteAt = SystemClock.elapsedRealtime()
        dirty = false
    }

    /** 从磁盘读取全部事件并填充缓存；无数据时返回 null。 */
    private fun loadUnlocked(): MutableList<GameEvent>? {
        val raw = preferences.getString(KEY_EVENTS, null) ?: return null
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    add(decodeEvent(array.getJSONObject(index)))
                }
            }.toMutableList()
        }.getOrNull()?.also { cachedEvents = it }
    }

    private fun encodeEvent(event: GameEvent): JSONObject = JSONObject()
        .put("id", event.id)
        .put("type", event.javaClass.simpleName)
        .also { json ->
            when (event) {
                is GameEvent.SelfHandLocked -> json.put("cards", encodeCounts(event.cards))
                    .put("cardCount", event.cardCount)
                is GameEvent.OutsideCardsPlayed -> {
                    json.put("cards", encodeCounts(event.cards))
                    json.put("cardCount", event.cardCount)
                    event.seat?.let { json.put("seat", it.name) }
                }
                is GameEvent.SelfCardsPlayed -> json.put("cards", encodeCounts(event.cards))
                    .put("cardCount", event.cardCount)
                is GameEvent.Reverted -> json.put("target", event.targetEventId)
                is GameEvent.SessionPaused -> json.put("reason", event.reason.name)
                else -> Unit
            }
        }

    private fun decodeEvent(json: JSONObject): GameEvent {
        val id = json.getString("id")
        return when (json.getString("type")) {
            "NewSession" -> GameEvent.NewSession(id)
            "SelfHandLocked" -> GameEvent.SelfHandLocked(
                decodeCounts(json.getJSONObject("cards")), id,
                json.optInt("cardCount", decodeCounts(json.getJSONObject("cards")).total())
            )
            "OutsideCardsPlayed" -> GameEvent.OutsideCardsPlayed(
                decodeCounts(json.getJSONObject("cards")),
                id,
                json.optString("seat").takeIf { it.isNotEmpty() }
                    ?.let { OutsideSeat.valueOf(it) },
                json.optInt("cardCount", decodeCounts(json.getJSONObject("cards")).total())
            )
            "SelfCardsPlayed" -> GameEvent.SelfCardsPlayed(
                decodeCounts(json.getJSONObject("cards")), id,
                json.optInt("cardCount", decodeCounts(json.getJSONObject("cards")).total())
            )
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

    companion object {
        const val WRITE_THROTTLE_MS = 500L

        private val SESSION_LOCK = Any()

        /**
         * 是否必须绕过节流立即落盘。
         *
         * 只有 [GameEvent.Reverted]（撤销一条历史记录）允许延后：它是纠错事件，
         * 丢了最多让账本短暂偏一次、下次落盘会补回，不会丢真实出牌。
         * 其余事件都是账本增量或会话边界——丢掉会直接破坏
         * `OutsideRemaining = 198 - SelfLockedHand - OutsidePlayed` 守恒，
         * 宁可多写一次磁盘也不能丢，所以一律立即落盘。
         */
        internal fun shouldWriteImmediately(
            event: GameEvent,
            now: Long,
            lastWriteAt: Long
        ): Boolean = event !is GameEvent.Reverted ||
            now - lastWriteAt >= WRITE_THROTTLE_MS

        private const val PREFERENCES = "card_counter_session"
        private const val KEY_EVENTS = "events"
        private const val KEY_POSITIVE_COUNT_MIGRATION = "positive_count_session_migration_v1"
    }
}

internal fun List<GameEvent>.withoutRecoverablePause(): List<GameEvent> =
    if (lastOrNull() is GameEvent.SessionPaused &&
        (last() as GameEvent.SessionPaused).reason in setOf(
            PauseReason.RANK_UNSTABLE,
            PauseReason.BASELINE_FAILURE
        )
    ) dropLast(1) else this

/** 首次完成迁移时的结果；[migrated] 为真表示磁盘日志已被整体替换成新会话。 */
internal data class PositiveCountSessionBootstrap(val state: GameState, val migrated: Boolean)

/**
 * 决定是否要丢弃旧事件日志。
 *
 * `markerPresent == false` 表示磁盘上的日志来自「正向累计版」之前，必须整体替换；
 * 已打过标记则原样沿用（[stored] 为 null 说明磁盘为空，同样起新会话）。
 */
internal fun positiveCountSessionBootstrap(
    markerPresent: Boolean,
    stored: GameState?
): PositiveCountSessionBootstrap = if (markerPresent) {
    PositiveCountSessionBootstrap(stored ?: GameReducer.newSession(), migrated = false)
} else {
    PositiveCountSessionBootstrap(GameReducer.newSession(), migrated = true)
}
