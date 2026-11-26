package com.example.cardcounter.ui

import com.example.cardcounter.domain.OutsideSeat

/**
 * 悬浮窗全部坐标（1280×578 设计坐标，和截屏同一套）。
 * 横屏时所有矩形都不许压识别区、不许进左侧挖孔和四角圆角；由 OverlayLayoutTest 守着。
 */
object OverlayLayout {
    const val DESIGN_WIDTH = 1_280
    const val DESIGN_HEIGHT = 578

    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
        val right: Int get() = x + width
        val bottom: Int get() = y + height

        fun overlaps(other: Rect): Boolean =
            x < other.right && other.x < right && y < other.bottom && other.y < bottom
    }

    /** 荣耀真机：横屏物理分辨率、左侧挖孔宽度、四角圆角半径（像素）。 */
    const val DEVICE_LANDSCAPE_WIDTH_PX = 2_664
    const val DEVICE_LANDSCAPE_HEIGHT_PX = 1_236
    const val DEVICE_CUTOUT_PX = 135
    const val DEVICE_CORNER_RADIUS_PX = 179

    /** 挖孔和圆角换算到设计坐标：横向、纵向缩放不同，圆角在设计坐标里是椭圆。 */
    const val CUTOUT_RIGHT = DEVICE_CUTOUT_PX * DESIGN_WIDTH / DEVICE_LANDSCAPE_WIDTH_PX + 1
    const val CORNER_RX = DEVICE_CORNER_RADIUS_PX * DESIGN_WIDTH / DEVICE_LANDSCAPE_WIDTH_PX + 1
    const val CORNER_RY = DEVICE_CORNER_RADIUS_PX * DESIGN_HEIGHT / DEVICE_LANDSCAPE_HEIGHT_PX + 1

    val seatPanels = linkedMapOf(
        OutsideSeat.TOP to Rect(439, 91, 350, 53),
        OutsideSeat.LEFT_TOP to Rect(49, 154, 344, 52),
        OutsideSeat.LEFT_BOTTOM to Rect(44, 322, 349, 52),
        OutsideSeat.RIGHT_TOP to Rect(868, 154, 344, 52),
        OutsideSeat.RIGHT_BOTTOM to Rect(866, 322, 346, 52)
    )
    val outsideRemaining = Rect(85, 495, 526, 76)

    /**
     * 顶部手牌点数条：15 个点数一整条、只读，画在不接收触摸的全屏显示层上。
     * 左边让开挖孔和圆角，右边停在 TOP 出牌区（x=690）左侧，下边停在左上出牌区裁剪区（y=62）上方；
     * 15 格每格约 40，"大"和"3"两头都完整可见。
     */
    val handBar = Rect(90, 0, 598, 58)

    /** 手牌还没锁定时，贴在"外面剩余"横条左上方的"手牌识别中"标签（只画不接触摸）。 */
    val handRecognizingTag = Rect(85, 469, 92, 26)

    /**
     * 停记块：标题、一行原因、一行"长按「开新局」"；只画不接触摸。
     * 落在 SELF 区右侧、右上座位框左侧、右下出牌区向左长串扩展最大范围（y 213 起）的上方，
     * 各边与禁区至少留 1 像素。
     */
    val stopBlock = Rect(724, 146, 142, 66)

    /** 小图标：固定 44dp 圆形，不随 1280×578 拉伸；这是横屏时它在设计坐标里的固定安全位置。 */
    const val ICON_DP = 44
    val iconLandscape = Rect(622, 505, 62, 60)

    /** "长按开新局"胶囊按钮：横屏在牌桌上才出现，在小图标右边。 */
    val holdNewGame = Rect(694, 505, 140, 58)

    /** 点小图标弹出的两项（打开记牌器、停止记牌），横屏时的位置；每项宽 140。 */
    val iconMenu = Rect(844, 505, 280, 56)

    /** 本次新增的横屏悬浮窗矩形：既不许压识别区，也不许进挖孔和四角圆角。 */
    val newLandscapeRects: List<Pair<String, Rect>> = listOf(
        "顶部手牌点数条" to handBar,
        "手牌识别中标签" to handRecognizingTag,
        "停记块" to stopBlock,
        "小图标" to iconLandscape,
        "长按开新局按钮" to holdNewGame,
        "小图标菜单" to iconMenu
    )

    /** 原有的横屏悬浮窗矩形（五个座位框、外面剩余横条）：不许压识别区；不动它们的位置。 */
    val existingLandscapeRects: List<Pair<String, Rect>> =
        seatPanels.map { (seat, rect) -> "${seat.displayName}出牌框" to rect } +
            listOf("外面剩余横条" to outsideRemaining)

    /** 横屏时所有会画出来或接触摸的悬浮窗矩形。 */
    val landscapeRects: List<Pair<String, Rect>> = newLandscapeRects + existingLandscapeRects
}
