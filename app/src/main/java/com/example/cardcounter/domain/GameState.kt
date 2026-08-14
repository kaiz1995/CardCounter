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
    val events: List<GameEvent> = emptyList()
) {
    val outsideRemaining: RankCounts?
        get() = selfLockedHand?.let { locked ->
            Rank.entries.takeIf { rank ->
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
        if (selfLockedHandCount != null) {
            check(outsidePlayedCount + selfPlayedCount + selfRemainingCount!! <= 198)
        } else {
            check(outsidePlayed == RankCounts.zero())
            check(outsidePlayedCount == 0)
        }
    }
}
