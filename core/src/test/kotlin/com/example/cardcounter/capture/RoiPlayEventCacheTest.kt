package com.example.cardcounter.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 帧名 "a-0" 的前缀（a）代表“哪一张画面”：前缀相同就是静止，前缀不同就是画面变了。 */
private fun samePicture(previous: String, current: String): Boolean =
    previous.substringBefore('-') == current.substringBefore('-')

class RoiPlayEventCacheTest {
    private fun cache(
        stableSamples: Int = 3,
        clearSamples: Int = 3,
        coveredStillSamples: Int = 3,
        isStill: (String, String) -> Boolean = ::samePicture,
        discard: (String) -> Unit = {}
    ) = RoiPlayEventCache<String>(
        stableSamples = stableSamples,
        clearSamples = clearSamples,
        coveredStillSamples = coveredStillSamples,
        isStill = isStill,
        discard = discard
    ).also { it.reset(generation = 1, primeExistingTable = false) }

    private fun RoiPlayEventCache<String>.see(frame: String, zone: PlayZone = PlayZone.TOP) =
        observe(zone, true, true, frame, 1)

    private fun RoiPlayEventCache<String>.empty(zone: PlayZone = PlayZone.TOP) =
        observe(zone, true, false, null, 1)

    /** 整张牌桌不可见（被弹窗、结算画面挡住）的一次采样。 */
    private fun RoiPlayEventCache<String>.hidden(zone: PlayZone = PlayZone.TOP, generation: Long = 1) =
        observe(zone, false, false, null, generation)

    @Test
    fun animationFramesNeverFinalize() {
        val cache = cache(isStill = { _, _ -> false })
        repeat(8) { index -> assertNull(cache.see("move-$index")) }
    }

    @Test
    fun finalizesOnlyAfterConsecutiveStillFrames() {
        val cache = cache()
        assertNull(cache.see("a-0"))
        assertNull(cache.see("a-1"))
        assertNull(cache.see("a-2"))
        assertEquals(listOf("a-0", "a-1", "a-2", "a-3"), requireNotNull(cache.see("a-3")).frames)
    }

    @Test
    fun deliversOnlyTheLastStillSegmentNotTheSlideInFrames() {
        val cache = cache()
        assertNull(cache.see("slide1-0"))
        assertNull(cache.see("slide2-0"))
        assertNull(cache.see("a-0"))
        assertNull(cache.see("a-1"))
        assertNull(cache.see("a-2"))
        assertEquals(listOf("a-0", "a-1", "a-2", "a-3"), requireNotNull(cache.see("a-3")).frames)
    }

    @Test
    fun samePictureStayingStillDeliversOnlyOnce() {
        val cache = cache(stableSamples = 2)
        cache.see("a-0")
        cache.see("a-1")
        assertEquals(3, requireNotNull(cache.see("a-2")).frames.size)
        repeat(20) { assertNull(cache.see("a-x$it")) }
    }

    @Test
    fun pictureThatBrieflyGoesEmptyAndComesBackDeliversAgain() {
        val cache = cache(stableSamples = 2, clearSamples = 3)
        cache.see("a-0")
        cache.see("a-1")
        assertTrue(cache.see("a-2") != null)
        assertNull(cache.empty())
        assertNull(cache.see("a-3"))
        assertNull(cache.see("a-4"))
        assertEquals(listOf("a-3", "a-4", "a-5"), requireNotNull(cache.see("a-5")).frames)
    }

    @Test
    fun pictureThatBrieflyMovesAndComesBackDeliversAgain() {
        val cache = cache(stableSamples = 2)
        cache.see("a-0")
        cache.see("a-1")
        assertTrue(cache.see("a-2") != null)
        assertNull(cache.see("glitch-0"))
        assertNull(cache.see("a-3"))
        assertNull(cache.see("a-4"))
        assertEquals(listOf("a-3", "a-4", "a-5"), requireNotNull(cache.see("a-5")).frames)
    }

    @Test
    fun newCardsCoveringOldOnesAreDeliveredWithoutAnEmptyGap() {
        val cache = cache(stableSamples = 2)
        cache.see("king-0")
        cache.see("king-1")
        assertTrue(cache.see("king-2") != null)
        assertNull(cache.see("pair-0"))
        assertNull(cache.see("pair-1"))
        assertEquals(listOf("pair-0", "pair-1", "pair-2"), requireNotNull(cache.see("pair-2")).frames)
    }

    @Test
    fun clearingAfterDeliveryDeliversNothingMoreAndKeepsWatching() {
        val cache = cache(stableSamples = 2, clearSamples = 2)
        cache.see("a-0")
        cache.see("a-1")
        assertTrue(cache.see("a-2") != null)
        assertNull(cache.empty())
        assertNull(cache.empty())
        assertTrue(cache.needsFrame(PlayZone.TOP, 1))
    }

    @Test
    fun clearingBeforeSettlementFinalizesOnceAsFallbackWithTheLastStillSegment() {
        val cache = cache(stableSamples = 5, clearSamples = 2)
        cache.see("slide-0")
        cache.see("a-0")
        cache.see("a-1")
        assertNull(cache.empty())
        assertEquals(listOf("a-0", "a-1"), requireNotNull(cache.empty()).frames)
        repeat(3) { assertNull(cache.empty()) }
    }

    @Test
    fun aChangedFrameAfterDeliveryIsNotDeliveredAsFallbackWhenTheZoneClears() {
        // 已交出后来一帧变化画面（收牌、淡出动画）：它没停稳过，清桌时不能当兜底交出。
        val discarded = mutableListOf<String>()
        val cache = cache(stableSamples = 2, clearSamples = 2, discard = discarded::add)
        cache.see("a-0")
        cache.see("a-1")
        assertTrue(cache.see("a-2") != null)
        assertNull(cache.see("fade-0"))
        assertFalse(cache.hasUndelivered(PlayZone.TOP, 1))
        assertNull(cache.empty())
        assertNull(cache.empty())
        repeat(3) { assertNull(cache.empty()) }
        assertTrue("fade-0" in discarded)
    }

    @Test
    fun aChangedFrameBeforeSettlementKeepsTheFallback() {
        // 没停稳（静止 2 次，停稳要 5 次）就被收走，收牌动画先拍到一帧变化画面：清桌时仍兜底交出原来的静止段。
        val discarded = mutableListOf<String>()
        val cache = cache(stableSamples = 5, clearSamples = 2, discard = discarded::add)
        cache.see("a-0")
        cache.see("a-1")
        cache.see("a-2")
        assertNull(cache.see("fade-0"))
        assertTrue(cache.hasUndelivered(PlayZone.TOP, 1))
        assertNull(cache.empty())
        assertEquals(listOf("a-0", "a-1", "a-2"), requireNotNull(cache.empty()).frames)
        assertFalse(cache.hasUndelivered(PlayZone.TOP, 1))
        assertEquals(listOf("fade-0"), discarded)
    }

    @Test
    fun newCardsThatSettleOverAnUnsettledPictureReplaceItsFallback() {
        // 旧的一段只静止过 1 次（低于被盖门槛 coveredStillSamples），新牌直接盖上、新画面自己静止过一次：
        // 旧的一段不算一手，放弃且不按被盖交出；清桌兜底交出的是新的一段。旧的一段静止够门槛时会按被盖交出，见上面的用例。
        val discarded = mutableListOf<String>()
        val cache = cache(stableSamples = 5, clearSamples = 2, discard = discarded::add)
        cache.see("a-0")
        cache.see("a-1")
        cache.see("b-0")
        assertTrue(cache.hasUndelivered(PlayZone.TOP, 1))
        assertNull("旧的一段低于被盖门槛，不交出", cache.see("b-1"))
        assertEquals(listOf("a-0", "a-1"), discarded)
        assertNull(cache.empty())
        assertEquals(listOf("b-0", "b-1"), requireNotNull(cache.empty()).frames)
    }

    @Test
    fun hasUndeliveredIsTrueOnlyForAPictureThatSettledOnceButWasNotDelivered() {
        val cache = cache(stableSamples = 3, clearSamples = 2)
        assertFalse(cache.hasUndelivered(PlayZone.TOP, 1))
        // 刚出现：还没比较到过静止。
        cache.see("a-0")
        assertFalse(cache.hasUndelivered(PlayZone.TOP, 1))
        // 静止过一次，还没停稳：清桌时会兜底交出。
        cache.see("a-1")
        assertTrue(cache.hasUndelivered(PlayZone.TOP, 1))
        assertFalse(cache.hasUndelivered(PlayZone.TOP, 2))
        assertFalse(cache.hasUndelivered(PlayZone.LEFT_TOP, 1))
        cache.see("a-2")
        assertTrue(cache.hasUndelivered(PlayZone.TOP, 1))
        // 已交出。
        assertTrue(cache.see("a-3") != null)
        assertFalse(cache.hasUndelivered(PlayZone.TOP, 1))
        // 换了画面又静止过一次，然后清桌兜底交出，交出后不再有待交出的牌。
        cache.see("b-0")
        assertFalse(cache.hasUndelivered(PlayZone.TOP, 1))
        cache.see("b-1")
        assertTrue(cache.hasUndelivered(PlayZone.TOP, 1))
        assertNull(cache.empty())
        assertTrue(cache.hasUndelivered(PlayZone.TOP, 1))
        assertEquals(listOf("b-0", "b-1"), requireNotNull(cache.empty()).frames)
        assertFalse(cache.hasUndelivered(PlayZone.TOP, 1))
    }

    @Test
    fun hasUndeliveredIsFalseForPrimedTableCards() {
        val cache = cache()
        cache.reset(generation = 1, primeExistingTable = true)
        cache.see("old-0")
        cache.see("old-1")
        assertFalse(cache.hasUndelivered(PlayZone.TOP, 1))
    }

    @Test
    fun primedTableCardsAreIgnoredUntilTheZoneClears() {
        val cache = cache()
        cache.reset(generation = 1, primeExistingTable = true)
        assertNull(cache.see("old-0"))
        assertFalse(cache.needsFrame(PlayZone.TOP, 1))
        repeat(3) { assertNull(cache.empty()) }
        assertTrue(cache.needsFrame(PlayZone.TOP, 1))
    }

    @Test
    fun repeatOfTheDeliveredPictureNeedsNoBitmapButAChangedPictureDoes() {
        val cache = cache(stableSamples = 2)
        assertFalse(cache.isRepeat(PlayZone.TOP, 1, "a-0"))
        cache.see("a-0")
        cache.see("a-1")
        assertFalse(cache.isRepeat(PlayZone.TOP, 1, "a-2"))
        assertTrue(cache.see("a-2") != null)
        assertTrue(cache.needsFrame(PlayZone.TOP, 1))
        assertTrue(cache.isRepeat(PlayZone.TOP, 1, "a-3"))
        assertFalse(cache.isRepeat(PlayZone.TOP, 1, "b-0"))
        assertNull(cache.see("b-0"))
        assertFalse(cache.isRepeat(PlayZone.TOP, 1, "b-1"))
    }

    @Test
    fun repeatIsFalseAfterTheZoneClearsOrForAnotherGeneration() {
        val cache = cache(stableSamples = 2, clearSamples = 2)
        cache.see("a-0")
        cache.see("a-1")
        assertTrue(cache.see("a-2") != null)
        assertFalse(cache.isRepeat(PlayZone.TOP, 2, "a-3"))
        cache.empty()
        cache.empty()
        assertFalse(cache.isRepeat(PlayZone.TOP, 1, "a-3"))
    }

    @Test
    fun stillFramesAfterDeliveryAreDiscardedNotKept() {
        val discarded = mutableListOf<String>()
        val cache = cache(stableSamples = 1, discard = discarded::add)
        cache.see("a-0")
        assertTrue(cache.see("a-1") != null)
        assertNull(cache.see("a-2"))
        assertEquals(listOf("a-2"), discarded)
    }

    @Test
    fun framesWhileTheTableIsHiddenAreDiscarded() {
        val discarded = mutableListOf<String>()
        val cache = cache(discard = discarded::add)
        assertNull(cache.observe(PlayZone.TOP, false, true, "x-0", 1))
        assertEquals(listOf("x-0"), discarded)
    }

    @Test
    fun observingANewGenerationDiscardsOldFrames() {
        val discarded = mutableListOf<String>()
        val cache = cache(stableSamples = 1, isStill = { _, _ -> true }, discard = discarded::add)
        cache.observe(PlayZone.TOP, true, true, "old", 1)
        cache.observe(PlayZone.TOP, true, true, "new", 2)
        assertEquals(listOf("old"), discarded)

        val finalized = requireNotNull(cache.observe(PlayZone.TOP, true, true, "new-still", 2))
        assertEquals(listOf("new", "new-still"), finalized.frames)
    }

    @Test
    fun reasonIsStableWhenThePictureSettlesNormally() {
        val cache = cache(stableSamples = 2)
        cache.see("a-0")
        cache.see("a-1")
        assertEquals(FinalizeReason.STABLE, requireNotNull(cache.see("a-2")).reason)
    }

    @Test
    fun unsettledHandCoveredByNewCardsIsDeliveredOnceTheNewPictureIsStill() {
        // A 静止 3 次（达到门槛）没停稳，B 直接盖上：B 静止一次时交出 A，之后 B 自己正常停稳再交出。
        val cache = cache(stableSamples = 10)
        repeat(4) { assertNull(cache.see("a-$it")) }
        assertNull(cache.see("b-0"))
        assertTrue(cache.hasUndelivered(PlayZone.TOP, 1))
        val covered = requireNotNull(cache.see("b-1"))
        assertEquals(FinalizeReason.COVERED, covered.reason)
        assertEquals(listOf("a-0", "a-1", "a-2", "a-3"), covered.frames)
        (2..9).forEach { assertNull(cache.see("b-$it")) }
        val settled = requireNotNull(cache.see("b-10"))
        assertEquals(FinalizeReason.STABLE, settled.reason)
        assertEquals("b-10", settled.frames.last())
    }

    @Test
    fun unsettledHandThatWasOnlyStillBrieflyAndGetsCoveredIsNotDelivered() {
        // A 只静止 2 次（滑入动画），低于门槛 3：被盖时放弃，不交出。
        val discarded = mutableListOf<String>()
        val cache = cache(stableSamples = 10, discard = discarded::add)
        repeat(3) { cache.see("a-$it") }
        assertNull(cache.see("b-0"))
        assertNull(cache.see("b-1"))
        assertTrue(listOf("a-0", "a-1", "a-2").all { it in discarded })
    }

    @Test
    fun unsettledHandIsDeliveredWhenTheTableBecomesHidden() {
        val cache = cache(stableSamples = 10)
        repeat(4) { assertNull(cache.see("a-$it")) }
        val finalized = requireNotNull(cache.hidden())
        assertEquals(FinalizeReason.TABLE_HIDDEN, finalized.reason)
        assertEquals(listOf("a-0", "a-1", "a-2", "a-3"), finalized.frames)
        assertEquals(1L, finalized.generation)
        // 同一手不重复交。
        assertNull(cache.hidden())
        assertFalse(cache.hasUndelivered(PlayZone.TOP, 1))
    }

    @Test
    fun coveredHandIsDeliveredWhenTheTableBecomesHidden() {
        // A 静止够久没停稳，B 刚盖上来还没静止：牌桌被挡，交出 A，B 丢掉。
        val discarded = mutableListOf<String>()
        val cache = cache(stableSamples = 10, discard = discarded::add)
        repeat(4) { cache.see("a-$it") }
        assertNull(cache.see("b-0"))
        val finalized = requireNotNull(cache.hidden())
        assertEquals(FinalizeReason.TABLE_HIDDEN, finalized.reason)
        assertEquals(listOf("a-0", "a-1", "a-2", "a-3"), finalized.frames)
        assertEquals(listOf("b-0"), discarded)
    }

    @Test
    fun handThatWasOnlyBrieflyStillIsKeptNotDeliveredWhenTheTableIsHidden() {
        val cache = cache(stableSamples = 10)
        cache.see("a-0")
        cache.see("a-1")
        assertNull(cache.hidden())
        // 没丢：牌桌回来后继续静止，清桌时仍按“静止过一次”兜底交出。
        assertTrue(cache.hasUndelivered(PlayZone.TOP, 1))
        repeat(2) { assertNull(cache.empty()) }
        assertEquals(FinalizeReason.CLEARED, requireNotNull(cache.empty()).reason)
    }

    @Test
    fun alreadyDeliveredHandIsNotDeliveredAgainWhenTheTableIsHidden() {
        val cache = cache(stableSamples = 2)
        cache.see("a-0")
        cache.see("a-1")
        assertTrue(cache.see("a-2") != null)
        assertNull(cache.hidden())
    }

    @Test
    fun hiddenTableNeverDeliversPrimedOldCardsOrAnotherGeneration() {
        val primed = cache(stableSamples = 10)
        primed.reset(generation = 1, primeExistingTable = true)
        repeat(4) { primed.see("old-$it") }
        assertNull(primed.hidden())

        val other = cache(stableSamples = 10)
        repeat(4) { other.see("a-$it") }
        assertNull(other.hidden(generation = 2))
        // 原 generation 里的缓存没被动。
        assertTrue(other.hasUndelivered(PlayZone.TOP, 1))
    }

    @Test
    fun handDeliveredWhileHiddenDeliversAgainWhenTheSamePictureSettlesAfterTheTableReturns() {
        // 去重由 StablePlayGate 负责，缓存只负责停稳：回来后同一画面停稳会再交出一次。
        val cache = cache(stableSamples = 4)
        repeat(4) { assertNull(cache.see("a-$it")) }
        assertEquals(FinalizeReason.TABLE_HIDDEN, requireNotNull(cache.hidden()).reason)
        (4..7).forEach { assertNull(cache.see("a-$it")) }
        assertEquals(FinalizeReason.STABLE, requireNotNull(cache.see("a-8")).reason)
    }

    @Test
    fun reasonIsClearedWhenTheZoneClearsBeforeSettling() {
        val cache = cache(stableSamples = 5, clearSamples = 2)
        cache.see("a-0")
        cache.see("a-1")
        assertNull(cache.empty())
        assertEquals(FinalizeReason.CLEARED, requireNotNull(cache.empty()).reason)
    }
}
