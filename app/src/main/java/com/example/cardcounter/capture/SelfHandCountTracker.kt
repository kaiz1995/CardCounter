package com.example.cardcounter.capture

internal sealed interface SelfHandCountChange {
    data class DecreaseObserved(val cardCount: Int) : SelfHandCountChange
    data class ReboundIgnored(
        val previous: Int,
        val current: Int,
        val suspectedNewGame: Boolean
    ) : SelfHandCountChange
}

/** Confirms stable geometric hand counts for diagnostics only; never creates a play event. */
internal class SelfHandCountTracker(
    private val stableSamplesRequired: Int,
    private val suspectedNewGameIncrease: Int
) {
    private var candidate: Int? = null
    private var stableSamples = 0
    private var lastStableCount: Int? = null

    fun reset(initialCount: Int?) {
        candidate = null
        stableSamples = 0
        lastStableCount = initialCount
    }

    fun observe(count: Int?): SelfHandCountChange? {
        if (count == null) {
            candidate = null
            stableSamples = 0
            return null
        }
        stableSamples = if (count == candidate) stableSamples + 1 else 1
        candidate = count
        if (stableSamples < stableSamplesRequired) return null
        val previous = lastStableCount ?: run {
            lastStableCount = count
            return null
        }
        if (count == previous) return null
        if (count > previous) {
            candidate = null
            stableSamples = 0
            return SelfHandCountChange.ReboundIgnored(
                previous,
                count,
                count - previous >= suspectedNewGameIncrease
            )
        }
        lastStableCount = count
        return SelfHandCountChange.DecreaseObserved(previous - count)
    }
}
