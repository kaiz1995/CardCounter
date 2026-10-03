package com.example.cardcounter.persistence

import android.content.Context
import android.util.Log
import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.storage.SaveResult
import com.example.cardcounter.storage.VersionedEventLog
import org.json.JSONArray
import org.json.JSONObject

/** 本地事件日志。首版不联网、不保存完整屏幕帧。 */
class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** 兼容旧调用方：只取状态，不关心序列号。 */
    fun load(): GameState? = loadWithSeq()?.first

    /**
     * 加载状态与序列号。新格式 {"schema":2,"seq":n,"events":[...]} 直接解码；
     * 旧格式（裸事件数组，无 seq 包装）按 seq=0 处理；损坏返回 null。
     * load 即 replay：重启不自动解除暂停，暂停状态按事件日志原样恢复。
     */
    fun loadWithSeq(): Pair<GameState, Int>? {
        val raw = preferences.getString(KEY_EVENTS, null) ?: return null
        return runCatching {
            VersionedEventLog.decode(raw)?.let { (seq, events) ->
                return@runCatching GameReducer.replay(events) to seq
            }
            // 旧格式兼容：裸事件数组，序列号视为 0。
            val array = JSONArray(raw)
            val events = buildList {
                for (index in 0 until array.length()) {
                    add(EventJsonCodec.decodeEvent(array.getJSONObject(index)))
                }
            }
            GameReducer.replay(events) to 0
        }.getOrNull()
    }

    /** CAS 保存：磁盘 seq 大于 expectedSeq 时拒绝旧版本覆盖，返回 false。 */
    fun save(state: GameState, expectedSeq: Int): Boolean {
        val current = preferences.getString(KEY_EVENTS, null)
        return when (val result = VersionedEventLog.saveIfCurrent(current, expectedSeq, state)) {
            is SaveResult.Ok -> {
                preferences.edit().putString(KEY_EVENTS, result.json).apply()
                true
            }
            SaveResult.StaleRejected -> {
                Log.w(TAG, "SAVE_STALE_REJECTED expectedSeq=$expectedSeq")
                false
            }
        }
    }

    /** 诊断记录持久化：独立 key，与事件日志互不影响。 */
    fun saveDiagnostics(json: String) {
        preferences.edit().putString(KEY_DIAGNOSTICS, json).apply()
    }

    fun loadDiagnostics(): String? = preferences.getString(KEY_DIAGNOSTICS, null)

    private companion object {
        const val PREFERENCES = "card_counter_session"
        const val KEY_EVENTS = "events"
        const val KEY_DIAGNOSTICS = "diagnostics_recent"
        const val TAG = "SessionStore"
    }
}

/** 兼容旧测试与旧调用方：事件编解码实现已迁移至 storage.VersionedEventLog。 */
internal object EventJsonCodec {
    fun encodeEvent(event: GameEvent): JSONObject = VersionedEventLog.encodeEvent(event)

    fun decodeEvent(json: JSONObject): GameEvent = VersionedEventLog.decodeEvent(json)
}
