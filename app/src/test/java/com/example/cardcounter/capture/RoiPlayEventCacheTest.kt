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

    /**
     * 交出后锁区，直到清空路径走满 clearSamples + clearedCooldownExtra 才放行。
     * 此处显式关掉后段冷却（extra=0），让本用例只验证「清空即解锁」的基础语义。
     */
    @Test
    fun finalizedZoneLocksUntilItClears() {
        val cache = RoiPlayEventCache<String>(
            clearSamples = 2,
            stableSamples = 1,
            clearedCooldownExtra = 0,
            isStill = { _, _ -> true }
        )
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
        val cache = RoiPlayEventCache<String>(
            clearSamples = 2,
            stableSamples = 3,
            clearedCooldownExtra = 0
        )
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

    /**
     * 清空路径是**两段串联相加**：先走满 clearSamples 确认离场，再额外走满 extra 抑制抖动重入。
     * 这里 clearSamples=2、extra=5，总计需要 7 个连续空帧才复位。
     */
    @Test
    fun clearPathRequiresClearSamplesPlusCooldownExtra() {
        val cache = RoiPlayEventCache<String>(
            clearSamples = 2,
            stableSamples = 1,
            clearedCooldownExtra = 5,
            isStill = { _, _ -> true }
        )
        cache.reset(generation = 1, primeExistingTable = false)
        cache.observe(PlayZone.LEFT_TOP, true, true, "start", 1)
        assertTrue(cache.observe(PlayZone.LEFT_TOP, true, true, "still", 1) != null)

        // 已走 clearSamples(2) 但后段冷却未满，仍锁区
        repeat(2) { assertNull(cache.observe(PlayZone.LEFT_TOP, true, false, null, 1)) }
        assertFalse(cache.needsFrame(PlayZone.LEFT_TOP, 1))

        // 后段 extra=5，再走 4 帧仍不够（累计 6 < 7）
        repeat(4) { assertNull(cache.observe(PlayZone.LEFT_TOP, true, false, null, 1)) }
        assertFalse(cache.needsFrame(PlayZone.LEFT_TOP, 1))

        // 第 7 帧：累计 2 + 5 = 7，复位
        assertNull(cache.observe(PlayZone.LEFT_TOP, true, false, null, 1))
        assertTrue(cache.needsFrame(PlayZone.LEFT_TOP, 1))
    }

    /**
     * 抖动场景：牌照稳交出后被清掉，随后**同一手牌**再现。
     * 后段冷却未走满时不得复位，避免同一手牌被二次 finalize。
     */
    @Test
    fun jitterBetweenClearAndReappearFinalizesOnlyOnce() {
        val cache = RoiPlayEventCache<String>(
            clearSamples = 6,
            stableSamples = 2,
            clearedCooldownExtra = 3,
            isStill = { _, current -> current.startsWith("still") }
        )
        cache.reset(generation = 1, primeExistingTable = false)

        cache.observe(PlayZone.RIGHT_TOP, true, true, "start", 1)
        cache.observe(PlayZone.RIGHT_TOP, true, true, "still-0", 1)
        val first = requireNotNull(cache.observe(PlayZone.RIGHT_TOP, true, true, "still-1", 1))
        assertEquals(listOf("start", "still-0", "still-1"), first.frames)

        // 牌面被清掉：只走 6 拍（= clearSamples），后段 extra=3 未走，仍锁区
        repeat(6) { assertNull(cache.observe(PlayZone.RIGHT_TOP, true, false, null, 1)) }
        assertFalse(cache.needsFrame(PlayZone.RIGHT_TOP, 1))

        // 同一手牌再现：仍处于锁区，不得二次交出
        assertNull(cache.observe(PlayZone.RIGHT_TOP, true, true, "start", 1))
        assertNull(cache.observe(PlayZone.RIGHT_TOP, true, true, "still-0", 1))
        assertNull(cache.observe(PlayZone.RIGHT_TOP, true, true, "still-1", 1))
        assertFalse(cache.needsFrame(PlayZone.RIGHT_TOP, 1))

        // 真正长时间清空后才恢复。注意上面的「同牌再现」会把空样本计数清零，
        // 因此这里必须重新走满 clearSamples(6) + extra(3) = 9 帧才能解锁。
        repeat(3) { assertFalse(cache.needsFrame(PlayZone.RIGHT_TOP, 1)) }
        repeat(9) { cache.observe(PlayZone.RIGHT_TOP, true, false, null, 1) }
        assertTrue(cache.needsFrame(PlayZone.RIGHT_TOP, 1))
    }

    /**
     * 预热路径（服务重启后桌面残留旧牌）只需走满 clearSamples，**不能**额外加冷却帧：
     * 预热阶段从未交出过牌，没有重复入账风险，多等只会拖慢识别恢复。
     */
    @Test
    fun primedZoneUnlocksAfterClearSamplesWithoutCooldownExtra() {
        val cache = RoiPlayEventCache<String>(
            clearSamples = 6,
            stableSamples = 1,
            clearedCooldownExtra = 3,
            isStill = { _, _ -> true }
        )
        cache.reset(generation = 1, primeExistingTable = true)
        assertFalse(cache.needsFrame(PlayZone.LEFT_BOTTOM, 1))

        // 前 5 帧不足以解锁
        repeat(5) { assertNull(cache.observe(PlayZone.LEFT_BOTTOM, true, false, null, 1)) }
        assertFalse(cache.needsFrame(PlayZone.LEFT_BOTTOM, 1))

        // 第 6 帧（= clearSamples）即应解锁，不需要等到 9 帧
        assertNull(cache.observe(PlayZone.LEFT_BOTTOM, true, false, null, 1))
        assertTrue(cache.needsFrame(PlayZone.LEFT_BOTTOM, 1))
    }
}
