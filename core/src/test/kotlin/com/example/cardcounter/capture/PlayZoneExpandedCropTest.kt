package com.example.cardcounter.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayZoneExpandedCropTest {
    private val width = 1_280
    private val height = 578

    private fun columnsFromRanges(vararg ranges: IntRange): (Int, Int, Int) -> Boolean =
        { x, _, _ -> ranges.any { x in it } }

    @Test
    fun doesNotExpandWhenCardsDoNotTouchExpandingEdge() {
        // RIGHT_BOTTOM base: left=960, width=141 -> 960..1100
        val rightBottomBase = PlayZone.RIGHT_BOTTOM.yoloCrop(width, height)
        assertEquals(
            rightBottomBase,
            PlayZone.RIGHT_BOTTOM.expandedYoloCrop(width, height, columnsFromRanges(990..1080))
        )

        // RIGHT_TOP base: left=960, width=141 -> 960..1100
        val rightTopBase = PlayZone.RIGHT_TOP.yoloCrop(width, height)
        assertEquals(
            rightTopBase,
            PlayZone.RIGHT_TOP.expandedYoloCrop(width, height, columnsFromRanges(975..1060))
        )

        // LEFT_BOTTOM base: left=180, width=181 -> 180..360
        val leftBottomBase = PlayZone.LEFT_BOTTOM.yoloCrop(width, height)
        assertEquals(
            leftBottomBase,
            PlayZone.LEFT_BOTTOM.expandedYoloCrop(width, height, columnsFromRanges(190..340))
        )

        // LEFT_TOP base: left=189, width=152 -> 189..340
        val leftTopBase = PlayZone.LEFT_TOP.yoloCrop(width, height)
        assertEquals(
            leftTopBase,
            PlayZone.LEFT_TOP.expandedYoloCrop(width, height, columnsFromRanges(200..320))
        )

        // TOP base: left=690, width=82 -> 690..771
        val topBase = PlayZone.TOP.yoloCrop(width, height)
        assertEquals(
            topBase,
            PlayZone.TOP.expandedYoloCrop(width, height, columnsFromRanges(695..750))
        )
    }

    @Test
    fun expandsRightZonesLeftwardWithGapStopMarginAndSafetyLimit() {
        // 连续白列到 850，之后有 >=12px 非白间隙（即使 810 处有噪声白列也不应越过间隙）
        val isWhite = columnsFromRanges(850..975, 800..820)
        val rightBottom = PlayZone.RIGHT_BOTTOM.expandedYoloCrop(width, height, isWhite)
        assertEquals(840, rightBottom.left)
        assertEquals(1_101 - 840, rightBottom.width)
        assertEquals(70, rightBottom.height)

        // 白列跨越小缝隙（<12px）并继续延伸
        val bridged = PlayZone.RIGHT_TOP.expandedYoloCrop(
            width,
            height,
            columnsFromRanges(900..950, 959..970) // 951..958 仅 8px 间隙，应继续扫到 900
        )
        assertEquals(890, bridged.left)
        assertEquals(1_101 - 890, bridged.width)
        assertEquals(56, bridged.height)

        // 超过安全左边界时钳制在安全线（RIGHT_BOTTOM=720, RIGHT_TOP=795）
        val clampedBottom = PlayZone.RIGHT_BOTTOM.expandedYoloCrop(width, height, columnsFromRanges(650..975))
        assertEquals(720, clampedBottom.left)
        assertEquals(1_101 - 720, clampedBottom.width)

        val clampedTop = PlayZone.RIGHT_TOP.expandedYoloCrop(width, height, columnsFromRanges(700..975))
        assertEquals(795, clampedTop.left)
        assertEquals(1_101 - 795, clampedTop.width)
    }

    @Test
    fun expandsLeftAndTopZonesRightwardWithGapStopMarginAndSafetyLimit() {
        // LEFT_BOTTOM base right=361 (含360), 白列延伸到 450, 留 10px 边距 -> newRight = 461
        val leftBottom = PlayZone.LEFT_BOTTOM.expandedYoloCrop(
            width,
            height,
            columnsFromRanges(350..450, 480..510) // 451..479 间隔 29px >= 12px
        )
        assertEquals(180, leftBottom.left)
        assertEquals(461 - 180, leftBottom.width)

        // LEFT_TOP 跨小缝隙（<=11px）并钳制到安全右边界 435
        val clampedLeftTop = PlayZone.LEFT_TOP.expandedYoloCrop(
            width,
            height,
            columnsFromRanges(330..380, 390..460) // 381..389 仅 9px
        )
        assertEquals(189, clampedLeftTop.left)
        assertEquals(435 - 189, clampedLeftTop.width)

        // TOP base left=690, right=772, 延伸到 920 -> newRight = 931
        val top = PlayZone.TOP.expandedYoloCrop(width, height, columnsFromRanges(760..920))
        assertEquals(690, top.left)
        assertEquals(931 - 690, top.width)

        // TOP 钳制到安全右边界 1040
        val clampedTop = PlayZone.TOP.expandedYoloCrop(width, height, columnsFromRanges(760..1150))
        assertEquals(690, clampedTop.left)
        assertEquals(1_040 - 690, clampedTop.width)
    }

    @Test
    fun selfZoneNeverExpands() {
        val base = PlayZone.SELF.yoloCrop(width, height)
        val expanded = PlayZone.SELF.expandedYoloCrop(width, height) { _, _, _ -> true }
        assertEquals(base, expanded)
    }
}
