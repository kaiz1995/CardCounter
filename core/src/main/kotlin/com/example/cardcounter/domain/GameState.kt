package com.example.cardcounter.domain

enum class SessionPhase {
    PRE_PLAY,
    ACTIVE,
    PAUSED,
    CLOSED
}

data class GameState(
    val phase: SessionPhase,
    val selfLockedHand: RankCounts? = null,
    val selfLockedHandCount: Int? = null,
    val selfPlayedCount: Int = 0,
    val outsidePlayed: RankCounts = RankCounts.zero(),
    val outsidePlayedCount: Int = 0,
    val outsidePlayedBySeat: Map<OutsideSeat, RankCounts> =
        OutsideSeat.entries.associateWith { RankCounts.zero() },
    val outsidePlayedCountBySeat: Map<OutsideSeat, Int> =
        OutsideSeat.entries.associateWith { 0 },
    val revertedEventIds: Set<String> = emptySet(),
    val pauseReason: PauseReason? = null,
    /**
     * 完整 Rank 账本是否仍然可信。任一 PARTIAL/UNKNOWN 事件被接受后永久为 false，
     * 此后 outsidePlayed（Rank 账）与 outsidePlayedCount（数量账）允许不一致。
     */
    val rankLedgerComplete: Boolean = true,
    val events: List<GameEvent> = emptyList()
) {
    /**
     * 外面剩余牌。只有在自己手牌带完整 Rank 向量、且 Rank 账本未被数量账事件
     * 降级时才返回数值；数量账场景（Rank 未知）返回 null，绝不伪造精确剩余。
     */
    val outsideRemaining: RankCounts?
        get() {
            val locked = selfLockedHand ?: return null
            if (!rankLedgerComplete) return null
            return Rank.entries.takeIf { rank ->
                rank.all { outsidePlayed[it] <= Rank.initialInventory[it] - locked[it] }
            }?.let { Rank.initialInventory.minus(locked).minus(outsidePlayed) }
        }

    val selfRemainingCount: Int?
        get() = selfLockedHandCount?.minus(selfPlayedCount)

    fun playedBy(seat: OutsideSeat): RankCounts =
        outsidePlayedBySeat[seat] ?: RankCounts.zero()

    fun playedCountBy(seat: OutsideSeat): Int = outsidePlayedCountBySeat[seat] ?: 0

    /** 买牌等前置转移尚未归因时，超过33张不伪造剩余数。 */
    fun requireInvariant() {
        check(Rank.initialInventory.total() == 198)
        check(selfLockedHandCount?.let { it > 0 } != false)
        check(selfPlayedCount >= 0)
        check(selfRemainingCount?.let { it >= 0 } != false)
        check(outsidePlayedCount >= 0)
        check(outsidePlayedCountBySeat.values.all { it >= 0 })
        check(outsidePlayedCountBySeat.values.sum() <= outsidePlayedCount)
        if (rankLedgerComplete) {
            check(outsidePlayed.total() == outsidePlayedCount) {
                "完整 Rank 账本必须与数量账一致: rankTotal=${outsidePlayed.total()}, count=$outsidePlayedCount"
            }
        }
        // rankLedgerComplete == false 时允许 rankTotal != count：数量账先行，Rank 账本不完整。
        if (selfLockedHandCount != null) {
            check(outsidePlayedCount + selfPlayedCount + selfRemainingCount!! <= 198)
        } else {
            check(outsidePlayed == RankCounts.zero())
            check(outsidePlayedCount == 0)
        }
    }
}
