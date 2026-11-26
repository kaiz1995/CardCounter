package com.example.cardcounter.domain

/** 新会话直接是 ACTIVE：外面出牌在确认手牌之前就要能记，手牌是否确认看 [GameState.handConfirmed]。 */
enum class SessionPhase {
    ACTIVE,
    PAUSED,
    CLOSED
}

data class GameState(
    val phase: SessionPhase,
    /** 本局已确认的自己手牌（点数完整向量）；null = 未确认。确认后本局不能再改。 */
    val selfHand: RankCounts? = null,
    /** 外面五家累计出的各点数。 */
    val outsidePlayed: RankCounts = RankCounts.zero(),
    /** 每一家各点数出了几张，各家各自累计。 */
    val outsidePlayedBySeat: Map<OutsideSeat, RankCounts> =
        OutsideSeat.entries.associateWith { RankCounts.zero() },
    val revertedEventIds: Set<String> = emptySet(),
    val pauseReason: PauseReason? = null,
    val events: List<GameEvent> = emptyList()
) {
    val handConfirmed: Boolean get() = selfHand != null

    /** 外面出了几张（五家合计）；由点数向量推出，不单独存账。 */
    val outsidePlayedCount: Int get() = outsidePlayed.total()

    /** 每个点数外面还剩几张 = 库存 − 自己手牌（已确认才扣）− 外面累计出牌。 */
    val outsideRemaining: RankCounts
        get() = Rank.initialInventory
            .minus(selfHand ?: RankCounts.zero())
            .minus(outsidePlayed)

    fun playedBy(seat: OutsideSeat): RankCounts =
        outsidePlayedBySeat[seat] ?: RankCounts.zero()

    /** 这一家累计出了几张；由点数向量推出。 */
    fun playedCountBy(seat: OutsideSeat): Int = playedBy(seat).total()

    /** 最近一条尚未撤销的外面出牌事件；没有则为 null。 */
    fun lastUnrevertedOutsidePlay(): GameEvent.OutsideCardsPlayed? =
        events.filterIsInstance<GameEvent.OutsideCardsPlayed>()
            .lastOrNull { it.id !in revertedEventIds }

    fun requireInvariant() {
        check(Rank.initialInventory.total() == 198)
        selfHand?.let { check(it.total() > 0) { "已确认的手牌不能为空" } }
        check(OutsideSeat.entries.all { it in outsidePlayedBySeat }) { "五家座位账必须齐全" }
        val seatSum = OutsideSeat.entries.fold(RankCounts.zero()) { sum, seat -> sum.plus(playedBy(seat)) }
        check(seatSum == outsidePlayed) { "五家点数向量之和必须等于外面累计: seats=$seatSum, outside=$outsidePlayed" }
        val own = selfHand ?: RankCounts.zero()
        check(Rank.entries.all { outsidePlayed[it] + own[it] <= Rank.initialInventory[it] }) {
            "每个点数 外面累计 + 自己手牌 不能超过库存"
        }
    }
}
