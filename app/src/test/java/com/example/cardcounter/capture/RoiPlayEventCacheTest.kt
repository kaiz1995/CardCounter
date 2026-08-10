package com.example.cardcounter.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoiPlayEventCacheTest {
    @Test
    fun commitsThreeStableFramesBeforeTheZoneClears() {
        val cache = RoiPlayEventCache<String>(clearSamples = 3, earlyFrames = 3)
        cache.reset(generation = 1, primeExistingTable = false)
        assertNull(cache.observe(PlayZone.RIGHT_TOP, true, true, "one", 1))
        assertNull(cache.observe(PlayZone.RIGHT_TOP, true, true, "two", 1))

        val finalized = cache.observe(PlayZone.RIGHT_TOP, true, true, "three", 1)
        requireNotNull(finalized)
        assertEquals(true, finalized.early)
        assertEquals(listOf("one", "two", "three"), finalized.frames)
        cache.completeEarly(PlayZone.RIGHT_TOP, 1)
        assertEquals(false, cache.needsFrame(PlayZone.RIGHT_TOP, 1))
    }

    @Test
    fun commitsOnlyAfterTheZoneHasCleared() {
        val cache = RoiPlayEventCache<String>(clearSamples = 3)
        cache.reset(generation = 1, primeExistingTable = false)
        assertNull(cache.observe(PlayZone.LEFT_TOP, true, true, "card", 1))
        repeat(2) { assertNull(cache.observe(PlayZone.LEFT_TOP, true, false, null, 1)) }

        val finalized = cache.observe(PlayZone.LEFT_TOP, true, false, null, 1)
        requireNotNull(finalized)
        assertEquals(PlayZone.LEFT_TOP, finalized.zone)
        assertEquals(listOf("card"), finalized.frames)
    }

    @Test
    fun shortAnimationGapDoesNotSplitOnePlayIntoTwo() {
        val cache = RoiPlayEventCache<String>(clearSamples = 3)
        cache.reset(generation = 1, primeExistingTable = false)
        cache.observe(PlayZone.RIGHT_BOTTOM, true, true, "first", 1)
        repeat(2) { cache.observe(PlayZone.RIGHT_BOTTOM, true, false, null, 1) }
        cache.observe(PlayZone.RIGHT_BOTTOM, true, true, "second", 1)
        repeat(2) { assertNull(cache.observe(PlayZone.RIGHT_BOTTOM, true, false, null, 1)) }

        val finalized = cache.observe(PlayZone.RIGHT_BOTTOM, true, false, null, 1)
        requireNotNull(finalized)
        assertEquals(listOf("first", "second"), finalized.frames)
    }

    @Test
    fun restartPrimeDiscardsCardsAlreadyOnTable() {
        val discarded = mutableListOf<String>()
        val cache = RoiPlayEventCache<String>(clearSamples = 2, discard = discarded::add)
        cache.reset(generation = 7, primeExistingTable = true)
        cache.observe(PlayZone.TOP, true, true, "existing", 7)
        repeat(2) { assertNull(cache.observe(PlayZone.TOP, true, false, null, 7)) }
        cache.observe(PlayZone.TOP, true, true, "new", 7)
        assertNull(cache.observe(PlayZone.TOP, true, false, null, 7))

        val finalized = cache.observe(PlayZone.TOP, true, false, null, 7)
        requireNotNull(finalized)
        assertEquals(listOf("existing"), discarded)
        assertEquals(listOf("new"), finalized.frames)
    }

    @Test
    fun restartPrimeCanBeReleasedBeforeReturningToTheTable() {
        val cache = RoiPlayEventCache<String>(clearSamples = 2)
        cache.reset(generation = 3, primeExistingTable = true)
        cache.releasePrime(generation = 3)
        cache.observe(PlayZone.TOP, true, true, "first", 3)
        assertNull(cache.observe(PlayZone.TOP, true, false, null, 3))

        val finalized = cache.observe(PlayZone.TOP, true, false, null, 3)
        requireNotNull(finalized)
        assertEquals(listOf("first"), finalized.frames)
    }

    @Test
    fun keepsTheLatestFramesWhenTheEventBufferIsFull() {
        val discarded = mutableListOf<String>()
        val cache = RoiPlayEventCache<String>(maxFrames = 2, clearSamples = 3, discard = discarded::add)
        cache.reset(generation = 5, primeExistingTable = false)
        assertEquals(true, cache.needsFrame(PlayZone.LEFT_BOTTOM, 5))
        cache.observe(PlayZone.LEFT_BOTTOM, true, true, "one", 5)
        cache.observe(PlayZone.LEFT_BOTTOM, true, true, "two", 5)
        cache.observe(PlayZone.LEFT_BOTTOM, true, true, "three", 5)
        assertEquals(true, cache.needsFrame(PlayZone.LEFT_BOTTOM, 5))
        assertEquals(listOf("one"), discarded)

        assertNull(cache.observe(PlayZone.LEFT_BOTTOM, true, false, null, 5))
        assertNull(cache.observe(PlayZone.LEFT_BOTTOM, true, false, null, 5))
        val finalized = cache.observe(PlayZone.LEFT_BOTTOM, true, false, null, 5)
        requireNotNull(finalized)
        assertEquals(listOf("two", "three"), finalized.frames)
    }
}
