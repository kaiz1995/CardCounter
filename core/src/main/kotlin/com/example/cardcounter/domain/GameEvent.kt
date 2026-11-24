package com.example.cardcounter.domain

import java.util.UUID

/** 事件日志保存自己锁牌、按座位的外面出牌和会话控制。 */
sealed interface GameEvent {
    val id: String

    data class NewSession(
        override val id: String = UUID.randomUUID().toString()
    ) : GameEvent

    data class SelfHandLocked(
        val cards: RankCounts,
        override val id: String = UUID.randomUUID().toString(),
        /** 手牌几何检测为数量账本；cards 仅保留牌面猜测。 */
        val cardCount: Int = cards.total(),
        /** UNKNOWN 时 cards 必须为全零，锁定只进数量账（selfLockedHand 为 null）。 */
        val rankCompleteness: RankCompleteness = RankCompleteness.COMPLETE
    ) : GameEvent

    data class OutsideCardsPlayed(
        val cards: RankCounts,
        override val id: String = UUID.randomUUID().toString(),
        val seat: OutsideSeat? = null,
        val cardCount: Int = cards.total(),
        /** COMPLETE 时 cardCount 必须等于 cards.total()，否则拒绝。 */
        val rankCompleteness: RankCompleteness = RankCompleteness.COMPLETE
    ) : GameEvent

    /** 自己出牌只做事件边界记录，绝不扣外面剩余。 */
    data class SelfCardsPlayed(
        val cards: RankCounts,
        override val id: String = UUID.randomUUID().toString(),
        val cardCount: Int = cards.total(),
        /** COMPLETE 时 cardCount 必须等于 cards.total()，否则拒绝。 */
        val rankCompleteness: RankCompleteness = RankCompleteness.COMPLETE
    ) : GameEvent

    data class Reverted(
        val targetEventId: String,
        override val id: String = UUID.randomUUID().toString()
    ) : GameEvent

    data class SessionPaused(
        val reason: PauseReason,
        override val id: String = UUID.randomUUID().toString()
    ) : GameEvent

    /**
     * 显式恢复：仅由人工/上层决策产生；重启回放不会生成该事件，
     * 因此暂停状态不会被重启自动解除。
     */
    data class SessionResumed(
        override val id: String = UUID.randomUUID().toString()
    ) : GameEvent

    data class SessionClosed(
        override val id: String = UUID.randomUUID().toString()
    ) : GameEvent
}

enum class PauseReason(val displayName: String) {
    BASELINE_FAILURE("基线失败"),
    ZONE_AMBIGUOUS("区位不唯一"),
    RANK_UNSTABLE("牌面不稳定"),
    UNKNOWN_SEMANTIC("未知语义"),
    NEW_GAME_UNCLEAR("新局不明"),
    CAPTURE_INTERRUPTED("截屏中断"),
    /** 任何出牌会导致超库存或总量守恒失败，原事件不进账本。 */
    CONSERVATION_FAILURE("守恒失败"),
    CORRUPTED_SESSION("会话损坏")
}
