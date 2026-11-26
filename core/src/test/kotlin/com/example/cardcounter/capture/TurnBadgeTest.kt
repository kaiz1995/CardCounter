package com.example.cardcounter.capture

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GREEN = 0x206030
private const val RED = 0xc02020
private const val YELLOW = 0xffd030
private const val DIMMED_RED = 0x701414
private const val DIMMED_YELLOW = 0x90761b

/** 画一个左红右黄、中心在 (cx, cy) 的圆标，其余是桌面绿。 */
private fun badgeAt(cx: Int, cy: Int): (Int, Int) -> Int = { x, y ->
    when {
        abs(x - cx) > 16 || abs(y - cy) > 12 -> GREEN
        x < cx -> RED
        else -> YELLOW
    }
}

class TurnBadgeTest {
    @Test
    fun seesTheBadgeOnlyInItsOwnZone() {
        PlayZone.entries.forEach { zone ->
            val pixel = badgeAt(zone.badgeX, zone.badgeY)
            PlayZone.entries.forEach { other ->
                assertEquals("$zone 圆标被 $other 看到", other == zone, TurnBadge.isShown(other, pixel))
            }
        }
    }

    @Test
    fun followsTheBadgeWhenItRisesAboveTheHangingCards() {
        PlayZone.entries.forEach { zone ->
            listOf(-12, -24, -30).forEach { offset ->
                assertTrue("$zone 偏移 $offset", TurnBadge.isShown(zone, badgeAt(zone.badgeX, zone.badgeY + offset)))
            }
            assertFalse("$zone 偏移 -60", TurnBadge.isShown(zone, badgeAt(zone.badgeX, zone.badgeY - 60)))
        }
    }

    @Test
    fun needsBothTheRedAndTheYellow() {
        val zone = PlayZone.TOP
        assertFalse(TurnBadge.isShown(zone) { _, _ -> GREEN })
        assertFalse(TurnBadge.isShown(zone) { _, _ -> RED })
        assertFalse(TurnBadge.isShown(zone) { _, _ -> YELLOW })
    }

    @Test
    fun recognizesDimmedBadgeByRelativeColorDifference() {
        val zone = PlayZone.RIGHT_BOTTOM
        val dimmedPixel: (Int, Int) -> Int = { x, y ->
            when {
                abs(x - zone.badgeX) > 16 || abs(y - zone.badgeY) > 12 -> 0x103018
                x < zone.badgeX -> DIMMED_RED
                else -> DIMMED_YELLOW
            }
        }
        assertTrue(TurnBadge.isShown(zone, dimmedPixel))
        // 压暗的白牌面（低饱和）不能误当成圆标。
        assertFalse(TurnBadge.isShown(zone) { _, _ -> 0x808080 })
    }
}

class TurnTrackerTest {
    @Test
    fun needsTwoConsecutiveSamples() {
        val tracker = TurnTracker()
        assertFalse(tracker.update(PlayZone.TOP, true, zoneEmpty = true))
        assertTrue(tracker.update(PlayZone.TOP, true, zoneEmpty = true))
    }

    @Test
    fun firesOncePerAppearance() {
        val tracker = TurnTracker()
        tracker.update(PlayZone.TOP, true, zoneEmpty = true)
        assertTrue(tracker.update(PlayZone.TOP, true, zoneEmpty = true))
        assertFalse(tracker.update(PlayZone.TOP, true, zoneEmpty = true))
        assertFalse(tracker.update(PlayZone.TOP, false, zoneEmpty = true))
        assertFalse(tracker.update(PlayZone.TOP, true, zoneEmpty = true))
        assertTrue(tracker.update(PlayZone.TOP, true, zoneEmpty = true))
    }

    @Test
    fun aGapRestartsTheCount() {
        val tracker = TurnTracker()
        assertFalse(tracker.update(PlayZone.TOP, true, zoneEmpty = true))
        assertFalse(tracker.update(PlayZone.TOP, false, zoneEmpty = true))
        assertFalse(tracker.update(PlayZone.TOP, true, zoneEmpty = true))
        assertTrue(tracker.update(PlayZone.TOP, true, zoneEmpty = true))
    }

    @Test
    fun zonesAreCountedSeparately() {
        val tracker = TurnTracker()
        assertFalse(tracker.update(PlayZone.TOP, true, zoneEmpty = true))
        assertFalse(tracker.update(PlayZone.LEFT_TOP, true, zoneEmpty = true))
        assertTrue(tracker.update(PlayZone.TOP, true, zoneEmpty = true))
        assertTrue(tracker.update(PlayZone.LEFT_TOP, true, zoneEmpty = true))
    }

    @Test
    fun resetForgetsEverything() {
        val tracker = TurnTracker()
        tracker.update(PlayZone.TOP, true, zoneEmpty = true)
        tracker.reset()
        assertFalse(tracker.update(PlayZone.TOP, true, zoneEmpty = true))
        assertTrue(tracker.update(PlayZone.TOP, true, zoneEmpty = true))
    }

    @Test
    fun aBadgeOverCardsStillOnTheTableWaitsUntilTheyAreGone() {
        // 挂王：上一手还摆着时圆标就亮了，不算轮到他；牌收走后圆标连续亮两次才算。
        val tracker = TurnTracker()
        assertFalse(tracker.update(PlayZone.RIGHT_BOTTOM, true, zoneEmpty = false))
        assertFalse(tracker.update(PlayZone.RIGHT_BOTTOM, true, zoneEmpty = false))
        assertFalse(tracker.update(PlayZone.RIGHT_BOTTOM, true, zoneEmpty = true))
        assertTrue(tracker.update(PlayZone.RIGHT_BOTTOM, true, zoneEmpty = true))
    }

    @Test
    fun longClearUnlocksAfter1500msButIgnores300msTransientEmpty() {
        val tracker = TurnTracker()
        assertFalse(tracker.track(PlayZone.TOP, shown = false, zoneEmpty = true, timestampMs = 1_000L).longCleared)
        // 300ms 瞬时闪空：不解锁。
        assertFalse(tracker.track(PlayZone.TOP, shown = false, zoneEmpty = true, timestampMs = 1_300L).longCleared)
        // 中途见到牌打断，重新计时。
        assertFalse(tracker.track(PlayZone.TOP, shown = false, zoneEmpty = false, timestampMs = 1_400L).longCleared)
        assertFalse(tracker.track(PlayZone.TOP, shown = false, zoneEmpty = true, timestampMs = 1_500L).longCleared)
        assertFalse(tracker.track(PlayZone.TOP, shown = false, zoneEmpty = true, timestampMs = 2_900L).longCleared)
        // 连续空桌满 1500ms：触发一次解锁，随后不再重复触发。
        assertTrue(tracker.track(PlayZone.TOP, shown = false, zoneEmpty = true, timestampMs = 3_000L).longCleared)
        assertFalse(tracker.track(PlayZone.TOP, shown = false, zoneEmpty = true, timestampMs = 3_500L).longCleared)
    }
}
