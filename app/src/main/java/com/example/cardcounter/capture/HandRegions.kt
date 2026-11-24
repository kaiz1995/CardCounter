package com.example.cardcounter.capture

/**
 * 自己手牌识别用到的屏幕区域（1280×578 设计坐标，右、下边界不含）。
 * 识别服务取图和悬浮窗布局测试共用这一份：手牌面板不许压在这些区域上。
 */
object HandRegions {
    data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** 手牌 YOLO 取图区。 */
    const val YOLO_LEFT = 180
    const val YOLO_RIGHT = 1_050
    const val YOLO_TOP = 350
    const val YOLO_BOTTOM = 490

    /** 手牌画面是否停稳的亮度指纹取样区（宽 5%~95%，高 58%~100%）。 */
    const val SIGNATURE_LEFT_PERCENT = 5
    const val SIGNATURE_RIGHT_PERCENT = 95
    const val SIGNATURE_TOP_PERCENT = 58

    fun yoloArea(): Area = Area(YOLO_LEFT, YOLO_TOP, YOLO_RIGHT, YOLO_BOTTOM)

    fun signatureArea(width: Int, height: Int): Area = Area(
        width * SIGNATURE_LEFT_PERCENT / 100,
        height * SIGNATURE_TOP_PERCENT / 100,
        width * SIGNATURE_RIGHT_PERCENT / 100,
        height
    )

    /** 按牌边缘数张数用的两排取样区。 */
    val edgeRowsArea: Area = Area(225, 420, 1_060, 541)

    /** 牌面模板匹配取墨迹的区域。 */
    val inkArea: Area = Area(180, 380, 1_080, 481)
}
