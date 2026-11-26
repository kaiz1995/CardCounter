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
    BASELINE_FAILURE("没看清牌桌，这一局记不准了"),
    ZONE_AMBIGUOUS("分不清是谁出的牌"),
    RANK_UNSTABLE("有一手牌一直没认清"),
    UNKNOWN_SEMANTIC("遇到了没见过的出牌情况"),
    NEW_GAME_UNCLEAR("分不清是不是新的一局"),
    CAPTURE_INTERRUPTED("截屏被中断了"),

    /** 手牌或外面出牌会让某个点数超过库存，原事件不进账本。 */
    CONSERVATION_FAILURE("有一手牌认错了，记的牌数对不上"),
    CORRUPTED_SESSION("这一局的记录损坏了"),

    /** 出牌阶段开始时没拿到合格的手牌（见 HandAutoLock）；不允许事后补认。 */
    HAND_NOT_RECOGNIZED("没认清自己的手牌")
}
