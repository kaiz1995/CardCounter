package com.example.cardcounter.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoiFingerprintTest {
    @Test
    fun smallChangesStayStillAndBigOnesDoNot() {
        val base = RoiFingerprint.of(PlayZone.TOP) { x, y -> (x * 3 + y * 5) and 0xffffff }
        fun changed(count: Int) = base.copyOf().also { for (i in 0 until count) it[i] = it[i] xor 0x800000 }
        assertTrue(RoiFingerprint.isStill(base, base.copyOf()))
        assertTrue(RoiFingerprint.isStill(base, changed(10)))
        assertFalse(RoiFingerprint.isStill(base, changed(100)))
        assertFalse(RoiFingerprint.isStill(base, base.copyOf(10)))
    }

    @Test
    fun samplesStayInsideTheZoneCrop() {
        PlayZone.entries.forEach { zone ->
            val crop = zone.yoloCrop(1_280, 578)
            RoiFingerprint.of(zone) { x, y ->
                assertTrue(x in crop.left until crop.left + crop.width)
                assertTrue(y in crop.top until crop.top + crop.height)
                0
            }
        }
    }
}
