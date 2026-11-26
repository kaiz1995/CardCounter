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

    /** 按牌边缘数张数的左右范围：下排牌最多 20 张时两头也要在里面，又不能伸进两边的头像、按钮。 */
    const val EDGE_MIN_X = 150
    const val EDGE_MAX_X = 1_130

    /**
     * 按牌边缘数张数用的两排取样区（取边缘要和左边一列相减，所以左边多取一列）。
     * 上排取整排的左边缘；下排只取被悬浮窗“外面剩余”横条盖住之前、露在横条上方的牌顶部。
     */
    val topEdgeRow: Area = Area(EDGE_MIN_X - 1, 420, EDGE_MAX_X + 1, 451)
    val bottomEdgeRow: Area = Area(EDGE_MIN_X - 1, 456, EDGE_MAX_X + 1, 469)

    /**
     * 下排牌白色顶边的取样区：y=452 是常规顶边上方，y=453..454 是同宽时的交界暗影，
     * y=455..458 是下排牌面白色顶边。用来分清下排读到的是下排自己的牌，还是上排外沿。
     */
    val bottomBorderRows: Area = Area(EDGE_MIN_X - 1, 452, EDGE_MAX_X + 1, 459)

    /** 牌面模板匹配取墨迹的区域。 */
    val inkArea: Area = Area(180, 380, 1_080, 481)
}
