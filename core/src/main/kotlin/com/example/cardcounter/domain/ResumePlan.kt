package com.example.cardcounter.domain

/**
 * 停记恢复要做的事：[revertTarget] 是要先撤销的那一手外面出牌，null 表示只恢复、不撤销。
 */
data class ResumePlan(val revertTarget: GameEvent.OutsideCardsPlayed?)

/**
 * 停记恢复的唯一入口判断：只有账本正处于停记（PAUSED）才有计划，否则返回 null、什么都不做。
 * 悬浮窗的按钮可能连点或点在过期的画面上，不是停记时再撤销会多撤掉一手正常出牌。
 * [revertLast] 为 true 时同时撤销最近一手没撤销的外面出牌（没有可撤的就只恢复）。
 * 手机的识别服务和悬浮窗兜底路径共用。
 */
fun resumePlan(state: GameState, revertLast: Boolean): ResumePlan? =
    if (state.phase != SessionPhase.PAUSED) {
        null
    } else {
        ResumePlan(if (revertLast) state.lastUnrevertedOutsidePlay() else null)
    }
