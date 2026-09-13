package com.example.cardcounter.ui

import com.example.cardcounter.domain.OutsideSeat

/**
 * 浮窗面板布局，坐标是 **1280x578 设计像素**（配合 [DESIGN_WIDTH]/[DESIGN_HEIGHT] 线性缩放）。
 *
 * 注意：这与 [com.example.cardcounter.capture.PlayZone] 的**比例**坐标**不是同一套坐标系**，
 * 两者用途不同——本坐标系是「显示用面板」（要容纳 15 格牌面信息），
 * PlayZone 是「识别用牌角带」（只扫描牌面点数所在的窄带）。
 *
 * 唯一硬约束是：每个座位面板必须**水平覆盖**对应 PlayZone 识别带，由
 * `OverlayLayoutTest.fiveSeatPanelsHorizontallyCoverMatchingOutsideZones` 锚定。
 * 面板的宽度与垂直位置与识别带**刻意不重合**（面板更宽、且位于识别带下方），
 * 这是显示需求使然。任何坐标调整都必须以真机视觉标定为依据，不要按几何中心推算。
 */
object OverlayLayout {
    const val DESIGN_WIDTH = 1_280
    const val DESIGN_HEIGHT = 578

    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

    val seatPanels = linkedMapOf(
        OutsideSeat.TOP to Rect(439, 91, 350, 53),
        OutsideSeat.LEFT_TOP to Rect(49, 154, 344, 52),
        OutsideSeat.LEFT_BOTTOM to Rect(44, 322, 349, 52),
        OutsideSeat.RIGHT_TOP to Rect(868, 154, 344, 52),
        OutsideSeat.RIGHT_BOTTOM to Rect(866, 322, 346, 52)
    )
    val outsideRemaining = Rect(85, 495, 526, 76)
    val hideButton = Rect(10, 499, 68, 68)
    val resetButton = Rect(645, 500, 69, 68)
}
