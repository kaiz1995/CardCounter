package com.example.cardcounter.persistence

import android.content.Context
import android.util.Log
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.storage.SaveResult
import com.example.cardcounter.storage.VersionedEventLog

/** 本地事件日志。首版不联网、不保存完整屏幕帧。 */
class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /**
     * 加载状态与序列号。磁盘上没有数据返回 null；当前版本按事件日志回放，
     * 旧版本或损坏的数据直接丢弃，返回全新会话和 seq=0，不做兼容迁移。
     * 重启不自动解除暂停，暂停状态按事件日志原样恢复。
     */
    fun loadWithSeq(): Pair<GameState, Int>? {
        val raw = preferences.getString(KEY_EVENTS, null) ?: return null
        return VersionedEventLog.restoreOrNew(raw)
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
