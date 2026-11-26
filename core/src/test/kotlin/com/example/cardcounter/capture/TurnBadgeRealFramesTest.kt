package com.example.cardcounter.capture

import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 真实截图上的圆标识别。截图来自一局真实对局（整屏缩成 1280x578，与手机取色同一套换算），
 * 为控制体积只保留各家圆标的取色窗口，其余涂黑；期望值是与“出牌”圆标模板比对的结果。
 */
class TurnBadgeRealFramesTest {
    private fun shownZones(stem: String): Set<PlayZone> {
        val image = requireNotNull(javaClass.getResourceAsStream("/turn_badge/$stem.png")) { "缺少夹具 $stem" }
            .use { ImageIO.read(it) }
        assertEquals(1280, image.width)
        assertEquals(578, image.height)
        return PlayZone.entries.filter { zone -> TurnBadge.isShown(zone) { x, y -> image.getRGB(x, y) and 0xffffff } }.toSet()
    }

    @Test
    fun seesTheBadgeOfEachSeat() {
        assertEquals(setOf(PlayZone.LEFT_TOP), shownZones("1795569737023"))
        assertEquals(setOf(PlayZone.RIGHT_TOP), shownZones("1795569742272"))
        assertEquals(setOf(PlayZone.LEFT_BOTTOM), shownZones("1795569612742"))
        assertEquals(setOf(PlayZone.RIGHT_BOTTOM), shownZones("1795569682708"))
        assertEquals(setOf(PlayZone.TOP), shownZones("1795569730540"))
        assertEquals(setOf(PlayZone.SELF), shownZones("1795569747587"))
    }

    @Test
    fun seesTheBadgeWhenItRisesAboveHangingCards() {
        assertEquals(setOf(PlayZone.RIGHT_BOTTOM), shownZones("1795569687901"))
    }

    @Test
    fun doesNotCountABadgeMovingBetweenSeatsAsEitherSeat() {
        assertEquals(emptySet<PlayZone>(), shownZones("1795569567722"))
    }

    @Test
    fun doesNotTakeTheOpponentAvatarRedBorderAsTop() {
        assertEquals(setOf(PlayZone.LEFT_TOP), shownZones("1795569631700"))
    }

    @Test
    fun seesNothingWhenNobodyHasTheBadge() {
        assertEquals(emptySet<PlayZone>(), shownZones("1795569740419"))
    }

    @Test
    fun seesDimmedBadgesAcrossRealFrames() {
        fun dimmedShownZones(stem: String, factor: Double = 0.55): Set<PlayZone> {
            val image = requireNotNull(javaClass.getResourceAsStream("/turn_badge/$stem.png")) { "缺少夹具 $stem" }
                .use { ImageIO.read(it) }
            return PlayZone.entries.filter { zone ->
                TurnBadge.isShown(zone) { x, y ->
                    val rgb = image.getRGB(x, y)
                    val r = (((rgb shr 16) and 0xff) * factor).toInt().coerceIn(0, 255)
                    val g = (((rgb shr 8) and 0xff) * factor).toInt().coerceIn(0, 255)
                    val b = ((rgb and 0xff) * factor).toInt().coerceIn(0, 255)
                    (r shl 16) or (g shl 8) or b
                }
            }.toSet()
        }
        assertEquals(setOf(PlayZone.LEFT_BOTTOM), dimmedShownZones("1795569612742"))
        assertEquals(setOf(PlayZone.RIGHT_BOTTOM), dimmedShownZones("1795569687901"))
        assertEquals(setOf(PlayZone.TOP), dimmedShownZones("1795569730540"))
        assertEquals(emptySet<PlayZone>(), dimmedShownZones("1795569567722"))
        assertEquals(emptySet<PlayZone>(), dimmedShownZones("1795569740419"))
    }
}
