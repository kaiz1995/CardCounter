package com.example.cardcounter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HandAutoLockTest {
    private val k = HandAutoLock.STABLE_FRAMES

    private fun reading(vararg pairs: Pair<Rank, Int>, position: Int? = pairs.sumOf { it.second }) =
        HandReading(RankCounts.of(mapOf(*pairs)), position)

    private fun HandAutoLock.feed(reading: HandReading, times: Int) = repeat(times) { observe(reading) }

    @Test
    fun locksTheStableReadingWhenTheFightPhaseStarts() {
        val lock = HandAutoLock()
        val hand = reading(Rank.KING to 2, Rank.ACE to 1)
        lock.feed(hand, k)

        val data = requireNotNull(lock.lock())
        assertEquals(hand.cards, data.cards)
        assertEquals(3, data.handCount)
    }

    @Test
    fun oneFrameShortOfKIsNotQualified() {
        val lock = HandAutoLock()
        lock.feed(reading(Rank.KING to 2), k - 1)
        assertNull(lock.lock())
    }

    @Test
    fun aChangedReadingRestartsTheCount() {
        val lock = HandAutoLock()
        lock.feed(reading(Rank.KING to 2), k - 1)
        lock.observe(reading(Rank.KING to 3))
        lock.feed(reading(Rank.KING to 2), k - 1)
        assertNull(lock.lock())
    }

    @Test
    fun rankTotalDifferentFromCountedCardsNeverQualifies() {
        val lock = HandAutoLock()
        lock.feed(reading(Rank.KING to 2, position = 3), k + 5)
        assertNull(lock.lock())
    }

    @Test
    fun uncountedPositionNeverQualifies() {
        val lock = HandAutoLock()
        lock.feed(reading(Rank.KING to 2, position = null), k + 5)
        assertNull(lock.lock())
    }

    @Test
    fun rankOverInventoryNeverQualifies() {
        val lock = HandAutoLock()
        // 3 的库存只有 6 张。
        lock.feed(reading(Rank.THREE to 7), k + 5)
        assertNull(lock.lock())
    }

    @Test
    fun noReadingAtAllMeansNoHand() {
        assertNull(HandAutoLock().lock())
    }

    @Test
    fun nothingChangesAfterLock() {
        val lock = HandAutoLock()
        val hand = reading(Rank.KING to 2)
        lock.feed(hand, k)
        assertNotNull(lock.lock())

        lock.feed(reading(Rank.ACE to 4), k + 3)
        assertEquals(hand.cards, requireNotNull(lock.lock()).cards)
    }

    @Test
    fun lateQualifiedReadingBeforeTheLockReplacesAnEarlierOne() {
        // 换牌期手牌先少一张再补回：补回后不是真子集，照常替换。
        val lock = HandAutoLock()
        lock.feed(reading(Rank.KING to 2, Rank.ACE to 1), k)
        lock.feed(reading(Rank.KING to 2, Rank.QUEEN to 1), k)
        assertEquals(RankCounts.of(mapOf(Rank.KING to 2, Rank.QUEEN to 1)), requireNotNull(lock.lock()).cards)
    }

    @Test
    fun aSubsetOfTheQualifiedHandMeansCardsWerePlayedAndIsNotUsed() {
        val lock = HandAutoLock()
        val full = reading(Rank.KING to 2, Rank.ACE to 1)
        lock.feed(full, k)
        lock.feed(reading(Rank.KING to 2), k + 2)
        assertEquals(full.cards, requireNotNull(lock.lock()).cards)
    }

    @Test
    fun resetStartsOverForTheNextGame() {
        val lock = HandAutoLock()
        lock.feed(reading(Rank.KING to 2), k)
        lock.lock()
        lock.reset()
        assertNull(lock.latest())
        lock.feed(reading(Rank.ACE to 1), k)
        assertEquals(RankCounts.of(mapOf(Rank.ACE to 1)), requireNotNull(lock.lock()).cards)
    }
}
