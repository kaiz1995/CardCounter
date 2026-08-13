package com.example.cardcounter.capture

import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import org.junit.Assert.assertEquals
import org.junit.Test

class PublicPlayCountAggregatorTest {
    private fun cards(vararg pairs: Pair<Rank, Int>) = RankCounts.of(pairs.toMap())

    @Test fun mergesBigJokerThenKingBody() {
        val a = PublicPlayCountAggregator(); val king = cards(Rank.KING to 6)
        assertEquals(emptyList<CountedPlay>(), a.offer(PlayZone.RIGHT_TOP, 1, 0, cards(Rank.BIG_JOKER to 1), 1))
        assertEquals(listOf(CountedPlay(PlayZone.RIGHT_TOP, king, 7)), a.offer(PlayZone.RIGHT_TOP, 1, 200, king, 6))
    }

    @Test fun mergesSmallJokerThenSevens() {
        val a = PublicPlayCountAggregator(); val sevens = cards(Rank.SEVEN to 6)
        a.offer(PlayZone.LEFT_BOTTOM, 1, 0, cards(Rank.SMALL_JOKER to 1), 1)
        assertEquals(7, a.offer(PlayZone.LEFT_BOTTOM, 1, 200, sevens, 6).single().cardCount)
    }

    @Test fun normalReplacementUsesNextCountOnly() {
        val a = PublicPlayCountAggregator(); val four = cards(Rank.FOUR to 4); val six = cards(Rank.SIX to 6)
        a.offer(PlayZone.TOP, 1, 0, four, 4)
        assertEquals(6, a.offer(PlayZone.TOP, 1, 200, six, 6).single().cardCount)
    }

    @Test fun duplicateAndTimeoutAndReset() {
        val a = PublicPlayCountAggregator(); val four = cards(Rank.FOUR to 4); val six = cards(Rank.SIX to 6)
        a.offer(PlayZone.TOP, 1, 0, four, 4)
        assertEquals(emptyList<CountedPlay>(), a.offer(PlayZone.TOP, 1, 100, four, 4))
        assertEquals(4, a.offer(PlayZone.TOP, 1, 500, six, 6).single().cardCount)
        assertEquals(emptyList<CountedPlay>(), a.offer(PlayZone.TOP, 2, 600, four, 4))
        repeat(6) { a.offer(PlayZone.TOP, 2, 700L + it, RankCounts.zero(), 0) }
        assertEquals(emptyList<CountedPlay>(), a.offer(PlayZone.TOP, 2, 800, four, 4))
    }
}
