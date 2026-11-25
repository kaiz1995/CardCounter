package com.example.cardcounter.ui

import com.example.cardcounter.capture.HandRegions
import com.example.cardcounter.capture.PlaySource
import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.capture.TurnBadge
import com.example.cardcounter.domain.Rank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLayoutTest {
    private val width = OverlayLayout.DESIGN_WIDTH
    private val height = OverlayLayout.DESIGN_HEIGHT

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

    /** 六个出牌区的“有牌判断带”和识别裁剪区，面板都不许压。 */
    private fun playZoneRects(): List<Pair<String, OverlayLayout.Rect>> = PlayZone.entries.flatMap { zone ->
        val left = zone.left(width)
        val right = zone.right(width)
        val rankY = zone.rankY(height)
        val crop = zone.yoloCrop(width, height)
        listOf(
            "${zone.name} 判断带" to OverlayLayout.Rect(left, rankY - 4, right - left + 1, zone.visibilityHeight + 5),
            "${zone.name} 裁剪区" to OverlayLayout.Rect(crop.left, crop.top, crop.width, crop.height)
        )
    }

    private fun areaRect(name: String, area: HandRegions.Area): Pair<String, OverlayLayout.Rect> =
        name to OverlayLayout.Rect(area.left, area.top, area.right - area.left, area.bottom - area.top)

    /** 自己手牌识别用到的全部区域。 */
    private fun handAreaRects(): List<Pair<String, OverlayLayout.Rect>> = listOf(
        areaRect("手牌 YOLO 取图区", HandRegions.yoloArea()),
        areaRect("手牌两排边缘取样区", HandRegions.edgeRowsArea),
        areaRect("手牌牌面墨迹区", HandRegions.inkArea),
        areaRect("手牌亮度指纹区", HandRegions.signatureArea(width, height))
    )

    private fun existingBoxes(): List<Pair<String, OverlayLayout.Rect>> =
        OverlayLayout.seatPanels.map { (seat, rect) -> "${seat.displayName}出牌框" to rect } + listOf(
            "外面剩余横条" to OverlayLayout.outsideRemaining,
            "隐藏按钮" to OverlayLayout.hideButton,
            "重置按钮" to OverlayLayout.resetButton
        )

    @Test
    fun handPanelWindowsStayOnScreen() {
        OverlayLayout.handPanelRects.forEach { rect ->
            assertTrue("$rect 超出屏幕", rect.x >= 0 && rect.y >= 0 && rect.right <= width && rect.bottom <= height)
        }
    }

    @Test
    fun handPanelDoesNotCoverPlayZones() {
        val zones = playZoneRects()
        OverlayLayout.handPanelRects.forEach { panel ->
            zones.forEach { (name, rect) ->
                assertFalse("手牌面板 $panel 压住了 $name $rect", panel.overlaps(rect))
            }
        }
    }

    @Test
    fun handPanelDoesNotCoverSelfHandRecognitionAreas() {
        val areas = handAreaRects()
        OverlayLayout.handPanelRects.forEach { panel ->
            areas.forEach { (name, rect) ->
                assertFalse("手牌面板 $panel 压住了 $name $rect", panel.overlaps(rect))
            }
        }
    }

    @Test
    fun handPanelDoesNotCoverExistingBoxes() {
        val boxes = existingBoxes()
        OverlayLayout.handPanelRects.forEach { panel ->
            boxes.forEach { (name, rect) ->
                assertFalse("手牌面板 $panel 压住了 $name $rect", panel.overlaps(rect))
            }
        }
    }

    @Test
    fun handPanelWindowsDoNotOverlapEachOther() {
        val rects = OverlayLayout.handPanelRects
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            assertFalse("${rects[i]} 和 ${rects[j]} 重叠", rects[i].overlaps(rects[j]))
        }
    }

    /** 亮度指纹区（整个下半屏）本来就被“外面剩余”横条和两个按钮盖着，标签沿用同一位置，不再单独比。 */
    @Test
    fun unconfirmedTagStaysOnScreenAndAvoidsEverythingItMust() {
        val tag = OverlayLayout.handUnconfirmedTag
        assertTrue("$tag 超出屏幕", tag.x >= 0 && tag.y >= 0 && tag.right <= width && tag.bottom <= height)
        (playZoneRects() + handAreaRects().filter { !it.first.contains("亮度指纹") } + existingBoxes()).forEach { (name, rect) ->
            assertFalse("未确认标签 $tag 压住了 $name $rect", tag.overlaps(rect))
        }
        OverlayLayout.handPanelRects.forEach {
            assertFalse("未确认标签 $tag 压住了手牌面板 $it", tag.overlaps(it))
        }
    }

    @Test
    fun confirmedChipFitsInsideStatusWindow() {
        val chip = OverlayLayout.handConfirmedChip
        val status = OverlayLayout.handStatus
        assertTrue(chip.x >= status.x && chip.y >= status.y && chip.right <= status.right && chip.bottom <= status.bottom)
    }

    @Test
    fun everyRankHasATouchTargetAtLeastMinimum() {
        val ranks = OverlayLayout.handRanksLeftRanks + OverlayLayout.handRanksRightRanks
        assertEquals(Rank.entries, ranks)
        assertTrue(OverlayLayout.handRanksLeft.width / OverlayLayout.handRanksLeftRanks.size >= OverlayLayout.MIN_TOUCH)
        assertTrue(OverlayLayout.handRanksRight.width / OverlayLayout.handRanksRightRanks.size >= OverlayLayout.MIN_TOUCH)
        assertTrue(OverlayLayout.handRanksLeft.height >= OverlayLayout.MIN_TOUCH)
        assertTrue(OverlayLayout.handRanksRight.height >= OverlayLayout.MIN_TOUCH)
    }

    /** 外面五家“出牌”圆标的取色窗（含边界像素）；悬浮窗会被截屏录进去，盖住就认不到圆标。 */
    private fun badgeWindows(): List<Pair<String, OverlayLayout.Rect>> =
        PlayZone.entries.filter { it.source == PlaySource.OUTSIDE }.map { zone ->
            val window = TurnBadge.window(zone)
            "${zone.name} 出牌圆标取色窗" to OverlayLayout.Rect(
                window.left, window.top, window.right - window.left + 1, window.bottom - window.top + 1
            )
        }

    /** 悬浮窗上所有会画出来或会接触摸的框：新增框要加到这里。 */
    private fun everyBoxOnTheOverlay(): List<Pair<String, OverlayLayout.Rect>> = existingBoxes() + listOf(
        "手牌点数条左段" to OverlayLayout.handRanksLeft,
        "手牌点数条右段" to OverlayLayout.handRanksRight,
        "手牌合计/确认窗" to OverlayLayout.handControl,
        "手牌状态窗" to OverlayLayout.handStatus,
        "手牌未确认标签" to OverlayLayout.handUnconfirmedTag,
        "手牌确认小条" to OverlayLayout.handConfirmedChip
    )

    @Test
    fun overlayBoxesDoNotCoverAnyTurnBadgeWindow() {
        val badges = badgeWindows()
        assertEquals(5, badges.size)
        everyBoxOnTheOverlay().forEach { (boxName, box) ->
            badges.forEach { (badgeName, badge) ->
                assertFalse(
                    "悬浮窗的「$boxName」$box 压住了「$badgeName」$badge：截屏里会盖住圆标，" +
                        "这一家永远认不到轮到他。请把这个框挪到圆标取色窗外面（TurnBadge.window 给出范围）",
                    box.overlaps(badge)
                )
            }
        }
    }

    @Test
    fun controlAndStatusWindowsAreBigEnoughToTouch() {
        listOf(OverlayLayout.handControl, OverlayLayout.handStatus, OverlayLayout.handConfirmedChip).forEach {
            assertTrue("$it 太小", it.width >= OverlayLayout.MIN_TOUCH && it.height >= OverlayLayout.MIN_TOUCH)
        }
    }
}
