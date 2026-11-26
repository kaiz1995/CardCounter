package com.example.cardcounter.domain

import java.util.UUID

/**
 * 事件日志只保存：本局唯一一次手牌确认、按座位的外面出牌和会话控制。
 * 自己出的牌完全不进账，没有对应事件。
 */
sealed interface GameEvent {
    val id: String

    data class NewSession(
        override val id: String = UUID.randomUUID().toString()
    ) : GameEvent

    /**
     * 本局唯一一次确认自己的手牌（按点数的完整向量）。
     * handCount 是按牌边缘数出来的张数，没数出来为 null，仅作记录，不参与校验（点数合计以 cards 为准）。
     */
    data class HandConfirmed(
        val cards: RankCounts,
        val handCount: Int?,
        override val id: String = UUID.randomUUID().toString()
    ) : GameEvent

    /** 外面某一家出的一手牌；只接受点数完整的出牌。 */
    data class OutsideCardsPlayed(
        val cards: RankCounts,
        val seat: OutsideSeat,
        override val id: String = UUID.randomUUID().toString()
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
    /** 手牌确认或外面出牌会让某个点数超过库存，原事件不进账本。 */
    CONSERVATION_FAILURE("守恒失败"),
    CORRUPTED_SESSION("会话损坏")
}
