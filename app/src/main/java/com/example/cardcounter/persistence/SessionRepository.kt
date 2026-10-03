package com.example.cardcounter.persistence

import android.content.Context
import android.util.Log
import com.example.cardcounter.domain.DispatchResult
import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.GameState

class SessionRepository(context: Context) {
    private val store = SessionStore(context.applicationContext)

    var state: GameState = store.load() ?: GameReducer.newSession()
        private set

    init {
        store.save(state)
    }

    /** 兼容旧调用方：返回派发后的账本状态；被拒事件不落账，返回原状态。 */
    fun dispatch(event: GameEvent): GameState = dispatchDetailed(event).state

    /** 完整派发结果：Accepted / AcceptedQuantityOnly / DuplicateIgnored / Rejected / Paused。 */
    fun dispatchDetailed(event: GameEvent): DispatchResult {
        val result = GameReducer.apply(state, event)
        when (result) {
            is DispatchResult.Rejected -> {
                // 被拒事件不写存储，账本保持原状态，原因只记录日志。
                Log.w(TAG, "DISPATCH_REJECTED id=${event.id} reason=${result.reason}")
            }
            else -> {
                state = result.state
                store.save(state)
            }
        }
        return result
    }

    fun newGame(): GameState {
        state = GameReducer.newSession()
        store.save(state)
        return state
    }

    private companion object {
        const val TAG = "SessionRepository"
    }
}
