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
    /** 外面剩余 = 固定库存 - 开局手牌快照 H0 - 外面已出；selfPlayed 只做审计，不参与。 */
    val outsideRemaining: RankCounts?
        get() = selfLockedHand?.let { Rank.initialInventory.minus(it).minus(outsidePlayed) }

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
        when (val locked = selfLockedHand) {
            null -> {
                check(selfPlayed == RankCounts.zero()) { "未锁定手牌时不允许自己出牌" }
                check(outsidePlayed == RankCounts.zero()) { "未锁定手牌时不允许外面出牌" }
            }
            else -> {
                check(locked.fitsWithin(Rank.initialInventory))
                val availableOutside = Rank.initialInventory.minus(locked)
                check(outsidePlayed.fitsWithin(availableOutside)) { "外面已出超过锁定后剩余" }
                val remaining = availableOutside.minus(outsidePlayed)
                Rank.entries.forEach { rank ->
                    check(
                        Rank.initialInventory[rank] ==
                            locked[rank] + outsidePlayed[rank] + remaining[rank]
                    )
                }
            }
        }
    }
}
