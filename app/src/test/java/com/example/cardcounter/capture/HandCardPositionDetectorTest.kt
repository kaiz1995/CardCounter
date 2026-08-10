package com.example.cardcounter.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class HandCardPositionDetectorTest {
    private val detector = HandCardPositionDetector()

    /** G2 几何诊断回归；该值尚不能作为锁牌依据。 */
    @Test
    fun reportsCurrentGeometryDiagnostics() {
        assertEquals(32, detector.detect(luma("145252")))
        assertEquals(33, detector.detect(luma("145333")))
        assertEquals(34, detector.detect(luma("145704")))
    }

    @Test
    fun keepsTheTwoRowsSeparate() {
        assertEquals(16 to 17, detector.rowCounts(luma("145333")))
    }

    private fun luma(name: String): IntArray {
        val image = Class.forName("javax.imageio.ImageIO")
            .getMethod("read", java.io.InputStream::class.java)
            .invoke(null, requireNotNull(javaClass.getResourceAsStream("/g2/$name.png")))
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val getRgb = image.javaClass.getMethod("getRGB", Int::class.java, Int::class.java)
        return IntArray(width * height) { index ->
            val color = getRgb.invoke(image, index % width, index / width) as Int
            val red = color shr 16 and 0xff
            val green = color shr 8 and 0xff
            val blue = color and 0xff
            (red * 30 + green * 59 + blue * 11) / 100
        }
    }
}
