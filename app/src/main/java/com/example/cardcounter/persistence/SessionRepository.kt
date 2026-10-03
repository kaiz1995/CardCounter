package com.example.cardcounter.persistence

import android.content.Context
import android.util.Log
import com.example.cardcounter.domain.DispatchResult
import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.storage.LedgerGateway

class SessionRepository(context: Context) : LedgerGateway {
    private val store = SessionStore(context.applicationContext)

    override var state: GameState
        private set

    /** 存储序列号（CAS 期望值）：每次成功 save 后更新为 state.events.size。 */
    @Volatile
    var seq: Int = 0
        private set

    init {
        val loaded = store.loadWithSeq()
        state = loaded?.first ?: GameReducer.newSession()
        seq = loaded?.second ?: 0
        persist(state)
    }

    /** 完整派发结果：Accepted / AcceptedQuantityOnly / DuplicateIgnored / Rejected / Paused。 */
    override fun dispatchDetailed(event: GameEvent): DispatchResult {
        val result = GameReducer.apply(state, event)
        when (result) {
            is DispatchResult.Rejected -> {
                // 被拒事件不写存储，账本保持原状态，原因只记录日志。
                Log.w(TAG, "DISPATCH_REJECTED id=${event.id} reason=${result.reason}")
            }
            else -> {
                state = result.state
                persist(state)
            }
        }
        return result
    }

    /** CAS 落盘：磁盘 seq 大于本地期望时拒绝写入，序列号不推进。 */
    private fun persist(target: GameState) {
        if (store.save(target, seq)) {
            seq = target.events.size
        }
    }

    private companion object {
        const val TAG = "SessionRepository"
    }
}
