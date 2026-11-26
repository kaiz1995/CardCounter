package com.example.cardcounter.domain

import com.example.cardcounter.vision.YoloDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandAutoLockTest {
    private val k = HandAutoLock.STABLE_FRAMES

    private fun det(rank: Rank, cx: Float, cy: Float) =
        YoloDetection(rank, rank.name, 0.8f, cx, cy, 7f, 11f)

    private fun reading(
        vararg pairs: Pair<Rank, Int>,
        position: Int? = pairs.sumOf { it.second },
        structureValid: Boolean = true
    ) = HandReading(RankCounts.of(mapOf(*pairs)), position, structureValid)

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

    @Test
    fun structureGuardBlocksNonMonotonicRankMisclassificationEvenWithMatchingTotal() {
        // Q 认成 9 且排在 J 左边：总张数对上 4==4，但从左到右不单调，独立防线拦截。
        val dets = listOf(
            det(Rank.KING, 20f, 222f),
            det(Rank.QUEEN, 36f, 222f),
            det(Rank.NINE, 15f, 244f), // 黑桃 Q 认成 9
            det(Rank.JACK, 31f, 244f)
        )
        assertFalse(HandLockGuard.isStructureValid(dets, 2 to 2))

        val lock = HandAutoLock()
        val cards = RankCounts.of(mapOf(Rank.KING to 1, Rank.QUEEN to 1, Rank.JACK to 1, Rank.NINE to 1))
        val badReading = HandLockGuard.buildReading(cards, dets, 2 to 2)
        lock.feed(badReading, k + 2)
        assertNull(lock.lock())
    }

    @Test
    fun structureGuardChecksPerRowCountsAndAllowsPoppedBottomCards() {
        // 下排有牌点中弹起（cy=235），仍在 ROW_SPLIT_Y(229) 下方且保持单调不增，不应误伤。
        val dets = listOf(
            det(Rank.KING, 20f, 222f),
            det(Rank.QUEEN, 36f, 222f),
            det(Rank.JACK, 15f, 244f),
            det(Rank.FIVE, 31f, 235f)
        )
        assertTrue(HandLockGuard.isStructureValid(dets, 2 to 2))
        assertFalse("分排张数不一致（上2下2 != 上1下3）必须拦住", HandLockGuard.isStructureValid(dets, 1 to 3))
    }

    @Test
    fun staleQualifiedIsClearedWhenPostExchangeHandAddsNewCardsButFailsGuards() {
        // 换牌前 554s 形成旧 qualified，换牌后多了 1 张 TWO 但把 Q 认成 9 致防线失败：必须作废旧 qualified，宁可停记绝不错锁。
        val lock = HandAutoLock()
        val preExchange = reading(Rank.TWO to 4, Rank.QUEEN to 4, Rank.JACK to 4)
        lock.feed(preExchange, k)

        val postExchangeBad = reading(
            Rank.TWO to 5, Rank.QUEEN to 3, Rank.JACK to 4, Rank.NINE to 1,
            position = 13,
            structureValid = false
        )
        lock.feed(postExchangeBad, k)
        assertNull(lock.lock())
    }

    @Test
    fun partialOccludedFrameWithoutPositionDoesNotClearQualified() {
        // 出牌/动画/遮挡期间如果张数未检出或与牌框数不齐，不能清掉之前已合格的完整手牌。
        val lock = HandAutoLock()
        val prePlay = reading(Rank.TWO to 3, Rank.QUEEN to 4)
        lock.feed(prePlay, k)

        val occludedNoPos = reading(Rank.TWO to 4, Rank.ACE to 1, position = null, structureValid = false)
        lock.feed(occludedNoPos, k + 2)
        assertEquals(prePlay.cards, requireNotNull(lock.lock()).cards)
    }
}
