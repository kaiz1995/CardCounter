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
        get() = selfLockedHand?.let { Rank.initialInventory.minus(it).minus(outsidePlayed) }

    fun playedBy(seat: OutsideSeat): RankCounts =
        outsidePlayedBySeat[seat] ?: RankCounts.zero()

    fun playedCountBy(seat: OutsideSeat): Int = outsidePlayedCountBySeat[seat] ?: 0

    fun requireInvariant() {
        check(Rank.initialInventory.total() == 198)
        check(selfLockedHand?.fitsWithin(Rank.initialInventory) != false)
        val assignedOutside = OutsideSeat.entries.fold(RankCounts.zero()) { total, seat ->
            total.plus(playedBy(seat))
        }
        check(assignedOutside.fitsWithin(outsidePlayed))
        check(outsidePlayedCount >= 0)
        check(outsidePlayedCountBySeat.values.all { it >= 0 })
        check(outsidePlayedCountBySeat.values.sum() <= outsidePlayedCount)
        val availableOutside = selfLockedHand?.let { Rank.initialInventory.minus(it) }
        check(availableOutside == null || outsidePlayed.fitsWithin(availableOutside))
        if (selfLockedHand != null) {
            val remaining = outsideRemaining!!
            Rank.entries.forEach { rank ->
                check(
                    Rank.initialInventory[rank] ==
                        selfLockedHand[rank] + outsidePlayed[rank] + remaining[rank]
                )
            }
        } else {
            check(outsidePlayed == RankCounts.zero())
        }
    }
}
