package com.example.cardcounter.domain

/**
 * 单个事件的派发结果。领域性拒绝（重复、数量与 Rank 不一致、超库存、守恒失败）
 * 一律以结果对象返回，不再抛异常，避免把可恢复问题放大成会话损坏。
 */
sealed interface DispatchResult {
    val state: GameState

    /** 事件进入完整账本（Rank + 数量）。 */
    data class Accepted(override val state: GameState) : DispatchResult

    /** 只进独立数量账；Rank 账本未动，此后 outsideRemaining 恒为 null。 */
    data class AcceptedQuantityOnly(override val state: GameState) : DispatchResult

    /** 重复 event.id：账本原样返回，不重复扣减。 */
    data class DuplicateIgnored(override val state: GameState) : DispatchResult

    /** 事件被拒绝：state 不包含被拒事件，调用方不得写入存储。 */
    data class Rejected(override val state: GameState, val reason: String) : DispatchResult

    /** 守恒失败等保护性暂停：state 追加 SessionPaused 事件，原事件不进 events。 */
    data class Paused(override val state: GameState, val reason: PauseReason) : DispatchResult
}
