package com.example.cardcounter.capture

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GREEN = 0x206030
private const val RED = 0xc02020
private const val YELLOW = 0xffd030

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
}
