package com.example.cardcounter.ui

import com.example.cardcounter.capture.PlaySource
import com.example.cardcounter.capture.PlayZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLayoutTest {
    @Test
    fun fiveSeatPanelsMatchFiveOutsideZonesAndDoNotCoverVisibilityBands() {
        val outsideZones = PlayZone.entries.filter { it.source == PlaySource.OUTSIDE }
        assertEquals(5, OverlayLayout.seatPanels.size)
        assertEquals(5, outsideZones.size)

        outsideZones.forEach { zone ->
            val panel = OverlayLayout.seatPanels.getValue(requireNotNull(zone.seat))
            assertTrue(panel.y >= zone.rankY(OverlayLayout.DESIGN_HEIGHT) + zone.visibilityHeight)
        }
    }

    /**
     * 面板与识别带是两套不同用途的坐标系：面板负责显示 15 格信息，识别带只扫牌角。
     * 宽度与垂直位置刻意不重合（真机视觉标定产物），但面板必须横向覆盖识别带，
     * 否则信息面板会脱离对应座位。此测试锚定这条唯一硬约束。
     */
    @Test
    fun fiveSeatPanelsHorizontallyCoverMatchingOutsideZones() {
        val outsideZones = PlayZone.entries.filter { it.source == PlaySource.OUTSIDE }

        outsideZones.forEach { zone ->
            val panel = OverlayLayout.seatPanels.getValue(requireNotNull(zone.seat))
            val zoneLeft = zone.left(OverlayLayout.DESIGN_WIDTH)
            val zoneRight = zone.right(OverlayLayout.DESIGN_WIDTH)

            assertTrue(
                "panel ${zone.seat} left=${panel.x} should not exceed zone left=$zoneLeft",
                panel.x <= zoneLeft
            )
            assertTrue(
                "panel ${zone.seat} right=${panel.x + panel.width} should reach zone right=$zoneRight",
                panel.x + panel.width >= zoneRight
            )
        }
    }
}
