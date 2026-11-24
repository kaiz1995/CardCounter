package com.example.cardcounter.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoiPlayEventCacheTest {
    @Test
    fun animationFramesNeverFinalize() {
        val cache = RoiPlayEventCache<String>(stableSamples = 3, isStill = { _, _ -> false })
        cache.reset(generation = 1, primeExistingTable = false)

        repeat(8) { index ->
            assertNull(cache.observe(PlayZone.RIGHT_TOP, true, true, "moving-$index", 1))
        }
    }

    @Test
    fun finalizesOnlyAfterConsecutiveStillFrames() {
        val cache = RoiPlayEventCache<String>(stableSamples = 3, isStill = { _, current -> current.startsWith("still") })
        cache.reset(generation = 1, primeExistingTable = false)
        assertNull(cache.observe(PlayZone.RIGHT_TOP, true, true, "start", 1))
        repeat(2) { assertNull(cache.observe(PlayZone.RIGHT_TOP, true, true, "still-$it", 1)) }

        val finalized = requireNotNull(
            cache.observe(PlayZone.RIGHT_TOP, true, true, "still-final", 1)
        )
        assertEquals(listOf("start", "still-0", "still-1", "still-final"), finalized.frames)
    }

    @Test
    fun finalizedZoneLocksUntilItClears() {
        val cache = RoiPlayEventCache<String>(clearSamples = 2, stableSamples = 1, isStill = { _, _ -> true })
        cache.reset(generation = 1, primeExistingTable = false)
        cache.observe(PlayZone.LEFT_TOP, true, true, "start", 1)
        assertTrue(cache.observe(PlayZone.LEFT_TOP, true, true, "still", 1) != null)
        assertFalse(cache.needsFrame(PlayZone.LEFT_TOP, 1))
        assertNull(cache.observe(PlayZone.LEFT_TOP, true, false, null, 1))
        assertNull(cache.observe(PlayZone.LEFT_TOP, true, false, null, 1))
        assertTrue(cache.needsFrame(PlayZone.LEFT_TOP, 1))
    }

    @Test
    fun clearingBeforeSettlementFinalizesOnceAsFallback() {
        val cache = RoiPlayEventCache<String>(clearSamples = 2, stableSamples = 3)
        cache.reset(generation = 1, primeExistingTable = false)
        cache.observe(PlayZone.RIGHT_BOTTOM, true, true, "moving", 1)
        assertNull(cache.observe(PlayZone.RIGHT_BOTTOM, true, false, null, 1))

        val fallback = requireNotNull(cache.observe(PlayZone.RIGHT_BOTTOM, true, false, null, 1))
        assertEquals(listOf("moving"), fallback.frames)
        repeat(3) { assertNull(cache.observe(PlayZone.RIGHT_BOTTOM, true, false, null, 1)) }
    }

    @Test
    fun observingANewGenerationDiscardsOldFrames() {
        val discarded = mutableListOf<String>()
        val cache = RoiPlayEventCache<String>(stableSamples = 1, isStill = { _, _ -> true }, discard = discarded::add)
        cache.reset(generation = 1, primeExistingTable = false)
        cache.observe(PlayZone.TOP, true, true, "old", 1)
        cache.observe(PlayZone.TOP, true, true, "new", 2)
        assertEquals(listOf("old"), discarded)

        val finalized = requireNotNull(cache.observe(PlayZone.TOP, true, true, "new-still", 2))
        assertEquals(listOf("new", "new-still"), finalized.frames)
    }
}
