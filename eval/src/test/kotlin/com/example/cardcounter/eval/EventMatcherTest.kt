package com.example.cardcounter.eval

import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventMatcherTest {
    private fun truth(
        seq: Int,
        zone: PlayZone = PlayZone.TOP,
        stableMs: Long = 1_000,
        clearMs: Long = 2_000,
        quantity: Int = 1,
        cards: RankCounts = RankCounts.of(mapOf(Rank.KING to 1))
    ) = TruthPlay(
        gameId = "g000001-1",
        eventSeq = seq,
        zone = zone,
        appearMs = stableMs - 100,
        stableMs = stableMs,
        clearMs = clearMs,
        quantity = quantity,
        cards = cards,
        hasRanks = true,
        tag = "NORMAL",
        review = "CONFIRMED",
        notes = "",
        lineNumber = seq + 1
    )

    private fun play(
        timestampMs: Long,
        zone: PlayZone = PlayZone.TOP,
        cards: RankCounts = RankCounts.of(mapOf(Rank.KING to 1)),
        cardCount: Int = cards.total()
    ) = AcceptedPlay(timestampMs, zone, cards, cardCount)

    @Test
    fun hitRequiresSameZoneAndWindow() {
        val result = EventMatcher.match(
            listOf(truth(1)),
            listOf(play(1_200))
        )

        assertEquals(1, result.hits.size)
        assertEquals(0, result.missed.size)
        assertEquals(0, result.extras.size)
        assertEquals(1.0, result.quantityExactRate, 0.0)
        assertEquals(1.0, result.rankExactRate, 0.0)
        assertEquals(1, result.confusion[Rank.KING.ordinal][Rank.KING.ordinal])
    }

    @Test
    fun outOfZoneRecordIsExtraAndTruthIsMissed() {
        val result = EventMatcher.match(
            listOf(truth(1)),
            listOf(play(1_200, zone = PlayZone.LEFT_TOP))
        )

        assertEquals(1, result.missed.size)
        assertEquals(1, result.extras.size)
        assertEquals(1, result.extrasOutside)
        assertEquals(0, result.hits.size)
    }

    @Test
    fun recordOutsideWindowIsExtra() {
        val result = EventMatcher.match(
            listOf(truth(1, stableMs = 1_000, clearMs = 2_000)),
            listOf(play(3_000))
        )

        assertTrue(result.hits.isEmpty())
        assertEquals(1, result.extras.size)
        assertEquals(1, result.missed.size)
    }

    @Test
    fun secondRecordForSameTruthIsDuplicate() {
        val result = EventMatcher.match(
            listOf(truth(1)),
            listOf(play(1_200), play(1_400))
        )

        assertEquals(1, result.hits.size)
        assertEquals(1, result.duplicates.size)
        assertEquals(truth(1).eventSeq, result.duplicates.single().truth.eventSeq)
        assertEquals(0, result.extras.size)
    }

    @Test
    fun quantityMismatchKeepsHitButFailsExactRate() {
        val result = EventMatcher.match(
            listOf(truth(1, quantity = 2, cards = RankCounts.of(mapOf(Rank.KING to 2)))),
            listOf(play(1_200, cards = RankCounts.of(mapOf(Rank.KING to 1)), cardCount = 1))
        )

        assertEquals(1, result.hits.size)
        assertEquals(0, result.quantityExact)
        assertEquals(0.0, result.quantityExactRate, 0.0)
        assertEquals(0, result.rankExact)
    }

    @Test
    fun confusionMarksWrongRankOffDiagonal() {
        val result = EventMatcher.match(
            listOf(truth(1, cards = RankCounts.of(mapOf(Rank.ACE to 1)))),
            listOf(play(1_200, cards = RankCounts.of(mapOf(Rank.TWO to 1))))
        )

        assertEquals(1, result.confusion[Rank.ACE.ordinal][Rank.TWO.ordinal])
        assertEquals(0, result.confusion[Rank.ACE.ordinal][Rank.ACE.ordinal])
    }

    @Test
    fun windowSlackIsConfigurable() {
        val strict = EventMatcher.match(
            listOf(truth(1, stableMs = 1_000, clearMs = 2_000)),
            listOf(play(2_500)),
            MatchWindows(beforeSlackMs = 0, afterSlackMs = 0)
        )
        val loose = EventMatcher.match(
            listOf(truth(1, stableMs = 1_000, clearMs = 2_000)),
            listOf(play(2_500)),
            MatchWindows(beforeSlackMs = 500, afterSlackMs = 500)
        )

        assertEquals(0, strict.hits.size)
        assertEquals(1, strict.extras.size)
        assertEquals(1, loose.hits.size)
    }
}
