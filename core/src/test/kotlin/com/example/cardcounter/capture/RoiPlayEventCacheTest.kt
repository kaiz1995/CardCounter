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
        isStill: (String, String) -> Boolean = ::samePicture,
        discard: (String) -> Unit = {}
    ) = RoiPlayEventCache<String>(
        stableSamples = stableSamples,
        clearSamples = clearSamples,
        isStill = isStill,
        discard = discard
    ).also { it.reset(generation = 1, primeExistingTable = false) }

    private fun RoiPlayEventCache<String>.see(frame: String, zone: PlayZone = PlayZone.TOP) =
        observe(zone, true, true, frame, 1)

    private fun RoiPlayEventCache<String>.empty(zone: PlayZone = PlayZone.TOP) =
        observe(zone, true, false, null, 1)

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
        assertTrue(cache.needsBitmap(PlayZone.TOP, 1))
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
    fun primedTableCardsAreIgnoredUntilTheZoneClears() {
        val cache = cache()
        cache.reset(generation = 1, primeExistingTable = true)
        assertNull(cache.see("old-0"))
        assertFalse(cache.needsFrame(PlayZone.TOP, 1))
        repeat(3) { assertNull(cache.empty()) }
        assertTrue(cache.needsFrame(PlayZone.TOP, 1))
    }

    @Test
    fun needsBitmapStopsAfterDeliveryUntilThePictureChanges() {
        val cache = cache(stableSamples = 2)
        assertTrue(cache.needsBitmap(PlayZone.TOP, 1))
        cache.see("a-0")
        cache.see("a-1")
        assertTrue(cache.see("a-2") != null)
        assertFalse(cache.needsBitmap(PlayZone.TOP, 1))
        assertTrue(cache.needsFrame(PlayZone.TOP, 1))
        assertNull(cache.see("b-0"))
        assertTrue(cache.needsBitmap(PlayZone.TOP, 1))
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
}
