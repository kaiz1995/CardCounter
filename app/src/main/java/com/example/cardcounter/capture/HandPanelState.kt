package com.example.cardcounter.capture

import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.HandDraftHolder
import com.example.cardcounter.domain.HandPanelStatus
import com.example.cardcounter.domain.handPanelStatus

/**
 * 识别服务（采集线程）和悬浮窗（主线程）共享的手牌面板状态，进程内唯一一份。
 * 手牌草稿自己加锁；被拒原因和“中途打开”标记是单个字段的读写，用 @Volatile 即可。
 */
object HandPanelState {
    val draft = HandDraftHolder()

    /** 上一次确认被拒的原因；手牌保持未确认，用户再改动、确认成功或新局时清掉。 */
    @Volatile
    var rejectReason: String? = null

    /** 中途打开，本局不记。 */
    @Volatile
    var skipped: Boolean = false

    /** 悬浮窗注册的刷新回调（在它自己的线程里重画）；没有悬浮窗时为 null。 */
    @Volatile
    var onChange: (() -> Unit)? = null

    /** 草稿、被拒原因或中途打开标记变了，通知悬浮窗重画。可在任意线程调用。 */
    fun changed() {
        onChange?.invoke()
    }

    /** [snapshot] 由调用方一次取好：悬浮窗要让显示的状态和“确认时认的版本”是同一版草稿。 */
    fun status(state: GameState, snapshot: com.example.cardcounter.domain.HandDraft = draft.snapshot()): HandPanelStatus =
        handPanelStatus(state, snapshot, rejectReason, skipped)

    /** 新局：草稿（含改过的标记）、被拒原因、中途打开标记全部清空。 */
    fun reset() {
        draft.clear()
        rejectReason = null
        skipped = false
        changed()
    }
}
