package com.example.cardcounter.capture

import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts

internal data class CountedPlay(
    val zone: PlayZone,
    val cards: RankCounts,
    val cardCount: Int
)

/** 合并同一区短间隔换牌；牌点仍取下一批，数量只用显式候选数和上一批王。 */
internal class PublicPlayCountAggregator(
    private val mergeWindowMs: Long = 300L,
    private val clearSamples: Int = 6
) {
    private data class Pending(
        val generation: Long,
        val timeMs: Long,
        val cards: RankCounts,
        val cardCount: Int
    )

    private val pending = mutableMapOf<PlayZone, Pending>()
    private val empty = mutableMapOf<PlayZone, Int>()

    fun offer(
        zone: PlayZone,
        generation: Long,
        timeMs: Long,
        cards: RankCounts,
        cardCount: Int
    ): List<CountedPlay> {
        require(cardCount >= 0)
        if (cards.total() == 0) {
            val n = (empty.getOrDefault(zone, 0) + 1).coerceAtMost(clearSamples)
            empty[zone] = n
            if (n >= clearSamples) return flush(zone, generation)
            return emptyList()
        }
        empty[zone] = 0
        val old = pending[zone]
        if (old == null || old.generation != generation) {
            pending[zone] = Pending(generation, timeMs, cards, cardCount)
            return if (old?.generation != null) emptyList() else emptyList()
        }
        if (cards == old.cards) return emptyList()
        if (timeMs - old.timeMs <= mergeWindowMs) {
            pending.remove(zone)
            val jokerCount = old.cards[Rank.BIG_JOKER] + old.cards[Rank.SMALL_JOKER]
            return listOf(CountedPlay(zone, cards, cardCount + jokerCount))
        }
        pending[zone] = Pending(generation, timeMs, cards, cardCount)
        return listOf(CountedPlay(zone, old.cards, old.cardCount))
    }

    fun flush(zone: PlayZone, generation: Long): List<CountedPlay> {
        val old = pending.remove(zone) ?: return emptyList()
        if (old.generation != generation) return emptyList()
        empty[zone] = 0
        return listOf(CountedPlay(zone, old.cards, old.cardCount))
    }

    fun reset() {
        pending.clear()
        empty.clear()
    }
}
