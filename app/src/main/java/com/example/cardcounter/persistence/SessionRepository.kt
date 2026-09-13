package com.example.cardcounter.persistence

import android.content.Context
import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.GameState

/**
 * 进程内单例仓库。构造是惰性的：只有第一次 [get] 才会读盘并在内存里建立状态。
 *
 * 单例化的原因是截屏热路径（[com.example.cardcounter.capture.CaptureProbeService] 每帧回调）
 * 原先每次 `SessionRepository(this)` 都会全量解析事件日志并回放一遍，是掉帧的直接来源。
 */
class SessionRepository private constructor(context: Context) {
    private val store = SessionStore(context.applicationContext)

    var state: GameState = store.loadOrMigrate()
        private set

    init {
        store.save(state)
    }

    fun dispatch(event: GameEvent): GameState {
        state = GameReducer.apply(state, event)
        store.apply(state, event)
        return state
    }

    fun newGame(): GameState {
        state = GameReducer.newSession()
        store.replaceWithNewSession(state)
        return state
    }

    /** 强制把尚未落盘的事件写入磁盘；服务销毁前必须调用。 */
    fun flush() {
        store.flush()
    }

    companion object {
        @Volatile
        private var instance: SessionRepository? = null

        fun get(context: Context): SessionRepository =
            instance ?: synchronized(this) {
                instance ?: SessionRepository(context.applicationContext).also { instance = it }
            }
    }
}
