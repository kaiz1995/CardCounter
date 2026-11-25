package com.example.cardcounter.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandCardPositionDetectorTest {
    private val detector = HandCardPositionDetector()

    /**
     * 目视数出来的两排张数（上排 to 下排），逐张放大核对过。
     * 133745 是 34 张（上 17、下 17）：队友进贡给了一张 3；
     * 102658_* 是带悬浮窗的整屏截图，下排上半被“外面剩余”横条盖住，只靠露在横条上方的牌顶部数。
     */
    private val truth = linkedMapOf(
        "133745" to (17 to 17),
        "145333" to (16 to 17),
        "145704" to (17 to 17),
        "102658_545" to (16 to 17),
        "102658_550" to (18 to 19),
        "102658_555" to (19 to 19),
        "102658_560" to (19 to 20),
        "102658_600" to (17 to 17),
        // 真机（Honor 2664×1236）按手机取样缩到 1280×578；224800 上排原来按整排跨度数成 17（16.73），总数显示 34。
        "w12_222900" to (16 to 17),
        "w12_224800" to (16 to 17)
    )

    @Test
    fun countsEachRowLikeTheNakedEye() {
        truth.forEach { (name, expected) ->
            val (top, bottom) = detector.rowCounts(luma(name))
            assertEquals("$name 上排", expected.first, top)
            assertEquals("$name 下排", expected.second, bottom)
            assertEquals("$name 总数", expected.first + expected.second, detector.detect(luma(name)))
        }
    }

    /** 回归：这一手 34 张，原来的固定窗口和共用牌距数成 33，悬浮窗显示就被换成了错的模板结果。 */
    @Test
    fun countsTheThirtyFourCardHandWithTributeCard() {
        assertEquals(17 to 17, detector.rowCounts(luma("133745")))
        assertEquals(34, detector.detect(luma("133745")))
    }

    /** 回归：真机这一手上排 16 张，整排跨度除固定间距算成 16.73 数成 17，“数出来”显示 34、确认按钮按不了。 */
    @Test
    fun topRowSpacingErrorsDoNotAddUpToAnExtraCard() {
        assertEquals(16 to 17, detector.rowCounts(luma("w12_224800")))
        assertEquals(33, detector.detect(luma("w12_224800")))
    }

    /** 两排张数差不在 0..1、某排没数出来或数出 0 张，都当没数出来，不能把错的张数交出去。 */
    @Test
    fun rowPairsThatCannotBeAWholeHandAreNotCounted() {
        assertEquals(34, detector.detect(17 to 17))
        assertEquals(33, detector.detect(16 to 17))
        assertNull("下排比上排多 2 张", detector.detect(16 to 18))
        assertNull("下排比上排少", detector.detect(17 to 16))
        assertNull("两排都是 0", detector.detect(0 to 0))
        assertNull("上排是 0", detector.detect(0 to 1))
        assertNull("上排没数出来", detector.detect(null to 17))
        assertNull("下排没数出来", detector.detect(17 to null))
    }

    @Test
    fun rowsOfTheWholeHandStayWithinOneOfEachOther() {
        truth.keys.forEach { name ->
            val (top, bottom) = detector.rowCounts(luma(name))
            // 游戏下排先排满，下排比上排多 0 或 1 张。
            assertEquals("$name", true, bottom!! - top!! in 0..1)
        }
    }

    /**
     * 145252 是进贡牌还在飞的过渡帧：目视上 16、下 15，下排比上排短，
     * 上排两头的牌从下排两侧伸出来，下排采样行读到的是上排外沿（两端和上排重合），数不出下排，只能是 null。
     */
    @Test
    fun bottomRowShorterThanTopReadsTheTopOutlineAndIsNotCounted() {
        val (top, bottom) = detector.rowCounts(luma("145252"))
        assertEquals(16, top)
        assertNull(bottom)
        assertNull(detector.detect(luma("145252")))
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
