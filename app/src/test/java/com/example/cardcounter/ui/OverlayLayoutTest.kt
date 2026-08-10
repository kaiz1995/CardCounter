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
}
