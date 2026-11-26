package com.example.cardcounter.ui

import com.example.cardcounter.capture.HandRegions
import com.example.cardcounter.capture.PlaySource
import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.capture.TABLE_GREEN_PROBES
import com.example.cardcounter.capture.TurnBadge
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
            val crop = zone.yoloCrop(width, height)
            assertTrue(panel.y >= crop.top + crop.height)
        }
    }

    /**
     * 出牌区的可见性判定区与识别裁剪区；长串大牌会向外扩展，扩展后的最大范围也不许压。
     * OUTSIDE 区扩展方向上的最远边界取自 PlayZone 的 expandLimitRatio，这里按最大范围算。
     */
    private fun playZoneRects(): List<Pair<String, OverlayLayout.Rect>> = PlayZone.entries.flatMap { zone ->
        val crop = zone.yoloCrop(width, height)
        val cropRect = "${zone.name} 裁剪区" to OverlayLayout.Rect(crop.left, crop.top, crop.width, crop.height)
        val maxRect = "${zone.name} 长串扩展最大范围" to maxExpandedRect(zone)
        if (zone.source == PlaySource.SELF) {
            val left = zone.left(width)
            val right = zone.right(width)
            val rankY = zone.rankY(height)
            listOf(
                "${zone.name} 判断带" to OverlayLayout.Rect(left, rankY - 4, right - left + 1, zone.visibilityHeight + 5),
                cropRect
            )
        } else {
            listOf(cropRect, maxRect)
        }
    }

    /** 用“全白列”恒真和恒假各取一次，得到向左/向右扩展到最远边界时裁剪框覆盖的范围。 */
    private fun maxExpandedRect(zone: PlayZone): OverlayLayout.Rect {
        val base = zone.yoloCrop(width, height)
        val expanded = zone.expandedYoloCrop(width, height) { _, _, _ -> true }
        return OverlayLayout.Rect(
            minOf(base.left, expanded.left),
            base.top,
            maxOf(base.left + base.width, expanded.left + expanded.width) - minOf(base.left, expanded.left),
            base.height
        )
    }

    private fun areaRect(name: String, area: HandRegions.Area): Pair<String, OverlayLayout.Rect> =
        name to OverlayLayout.Rect(area.left, area.top, area.right - area.left, area.bottom - area.top)

    /** 自己手牌识别用到的全部区域。 */
    private fun handAreaRects(): List<Pair<String, OverlayLayout.Rect>> = listOf(
        areaRect("手牌 YOLO 取图区", HandRegions.yoloArea()),
        areaRect("手牌上排边缘取样行", HandRegions.topEdgeRow),
        areaRect("手牌下排边缘取样行", HandRegions.bottomEdgeRow),
        areaRect("手牌下排白色顶边取样行", HandRegions.bottomBorderRows),
        areaRect("手牌牌面墨迹区", HandRegions.inkArea),
        areaRect("手牌亮度指纹区", HandRegions.signatureArea(width, height))
    )

    /** 外面五家“出牌”圆标的取色窗（含边界像素）；悬浮窗会被截屏录进去，盖住就认不到圆标。 */
    private fun badgeWindows(): List<Pair<String, OverlayLayout.Rect>> =
        PlayZone.entries.filter { it.source == PlaySource.OUTSIDE }.map { zone ->
            val window = TurnBadge.window(zone)
            "${zone.name} 出牌圆标取色窗" to OverlayLayout.Rect(
                window.left, window.top, window.right - window.left + 1, window.bottom - window.top + 1
            )
        }

    /** 亮度指纹区（整个下半屏）本来就被“外面剩余”横条和按钮盖着，不再单独比。 */
    private fun recognitionRectsWithoutSignature(): List<Pair<String, OverlayLayout.Rect>> =
        playZoneRects() + handAreaRects().filter { !it.first.contains("亮度指纹") } + badgeWindows()

    @Test
    fun landscapeRectsStayOnScreen() {
        OverlayLayout.landscapeRects.forEach { (name, rect) ->
            assertTrue(
                "「$name」$rect 超出屏幕",
                rect.x >= 0 && rect.y >= 0 && rect.right <= width && rect.bottom <= height
            )
        }
    }

    /** 悬浮窗会被截屏录进去：任何一个框盖住出牌区、手牌取样区或圆标取色窗，识别就会出错。 */
    @Test
    fun landscapeRectsDoNotCoverAnyRecognitionArea() {
        val areas = recognitionRectsWithoutSignature()
        OverlayLayout.landscapeRects.forEach { (boxName, box) ->
            areas.forEach { (areaName, area) ->
                assertFalse(
                    "悬浮窗的「$boxName」$box 压住了「$areaName」$area，请把这个框挪开",
                    box.overlaps(area)
                )
            }
        }
    }

    @Test
    fun landscapeRectsDoNotOverlapEachOther() {
        val rects = OverlayLayout.landscapeRects
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            assertFalse(
                "「${rects[i].first}」${rects[i].second} 和「${rects[j].first}」${rects[j].second} 重叠",
                rects[i].second.overlaps(rects[j].second)
            )
        }
    }

    @Test
    fun tableGreenProbesDoNotOverlapOverlayOrPlayZones() {
        val overlayBoxes = OverlayLayout.landscapeRects
        val playZones = playZoneRects()
        TABLE_GREEN_PROBES.forEach { (x, y) ->
            val probe = OverlayLayout.Rect(x, y, 1, 1)
            overlayBoxes.forEach { (boxName, box) ->
                assertFalse(
                    "牌桌绿布探针点 ($x, $y) 落在了悬浮窗「$boxName」$box 内，" +
                        "请把这个悬浮窗挪开或调整 TABLE_GREEN_PROBES 坐标",
                    probe.overlaps(box)
                )
            }
            playZones.forEach { (zoneName, zoneRect) ->
                assertFalse(
                    "牌桌绿布探针点 ($x, $y) 落在了出牌区「$zoneName」$zoneRect 内，" +
                        "请调整 TABLE_GREEN_PROBES 坐标避开出牌区",
                    probe.overlaps(zoneRect)
                )
            }
        }
    }

    /** 新增的横屏矩形不进左侧挖孔（x < CUTOUT_RIGHT）。 */
    @Test
    fun newLandscapeRectsStayOutOfCutout() {
        OverlayLayout.newLandscapeRects.forEach { (name, rect) ->
            assertTrue("「$name」$rect 进了左侧挖孔（x 要 >= ${OverlayLayout.CUTOUT_RIGHT}）", rect.x >= OverlayLayout.CUTOUT_RIGHT)
        }
    }

    /** 新增的横屏矩形四个角都不进屏幕四角的圆角（设计坐标里是椭圆）。 */
    @Test
    fun newLandscapeRectsStayOutOfRoundedCorners() {
        val rx = OverlayLayout.CORNER_RX.toDouble()
        val ry = OverlayLayout.CORNER_RY.toDouble()
        OverlayLayout.newLandscapeRects.forEach { (name, rect) ->
            val points = listOf(
                rect.x to rect.y,
                rect.right to rect.y,
                rect.x to rect.bottom,
                rect.right to rect.bottom
            )
            points.forEach { (px, py) ->
                val cx = if (px < width / 2) rx else width - rx
                val cy = if (py < height / 2) ry else height - ry
                val inCornerBox = (px < rx || px > width - rx) && (py < ry || py > height - ry)
                if (inCornerBox) {
                    val e = ((px - cx) / rx) * ((px - cx) / rx) + ((py - cy) / ry) * ((py - cy) / ry)
                    assertTrue("「$name」$rect 的角 ($px, $py) 落进了屏幕圆角", e <= 1.0)
                }
            }
        }
    }
}
