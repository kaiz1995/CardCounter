package com.example.cardcounter.domain

enum class SessionPhase {
    ACTIVE,
    PAUSED,
    CLOSED
}

data class GameState(
    val phase: SessionPhase,
    val selfPlayed: RankCounts = RankCounts.zero(),
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
    /** 外面剩余 = 固定库存 - 自己已出 - 外面已出；全部正数累加，不依赖开局锁手牌。 */
    val outsideRemaining: RankCounts
        get() = Rank.initialInventory.minus(selfPlayed).minus(outsidePlayed)

    fun playedBy(seat: OutsideSeat): RankCounts =
        outsidePlayedBySeat[seat] ?: RankCounts.zero()

    fun playedCountBy(seat: OutsideSeat): Int = outsidePlayedCountBySeat[seat] ?: 0

    fun requireInvariant() {
        check(Rank.initialInventory.total() == 198)
        check(selfPlayed.fitsWithin(Rank.initialInventory))
        check(outsidePlayed.fitsWithin(Rank.initialInventory.minus(selfPlayed)))
        val assignedOutside = OutsideSeat.entries.fold(RankCounts.zero()) { total, seat ->
            total.plus(playedBy(seat))
        }
        check(assignedOutside.fitsWithin(outsidePlayed))
        check(outsidePlayedCount >= 0)
        check(selfPlayedCount >= 0)
        check(outsidePlayedCountBySeat.values.all { it >= 0 })
        check(outsidePlayedCountBySeat.values.sum() <= outsidePlayedCount)
        val remaining = outsideRemaining
        Rank.entries.forEach { rank ->
            check(
                Rank.initialInventory[rank] ==
                    selfPlayed[rank] + outsidePlayed[rank] + remaining[rank]
            )
        }
    }
}
