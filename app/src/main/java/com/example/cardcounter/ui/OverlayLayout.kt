package com.example.cardcounter.ui

import com.example.cardcounter.domain.OutsideSeat
import com.example.cardcounter.domain.Rank

object OverlayLayout {
    const val DESIGN_WIDTH = 1_280
    const val DESIGN_HEIGHT = 578

    /** 可触摸按钮在设计坐标里的最小边长；按 1280×578 缩到常见手机屏约 40dp。 */
    const val MIN_TOUCH = 60

    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
        val right: Int get() = x + width
        val bottom: Int get() = y + height

        fun overlaps(other: Rect): Boolean =
            x < other.right && other.x < right && y < other.bottom && other.y < bottom
    }

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

    /**
     * 手牌面板：三块可触摸小窗，都落在出牌区、手牌区和现有框之间的空档里。
     * 点数条分左右两段（顶家出牌区和顶家“出牌”圆标取色窗夹在中间）：左段 8 个点数，右段 7 个点数，每格宽不小于 [MIN_TOUCH]。
     * 右段必须在顶家圆标取色窗（x 到 796）右边，否则截屏里圆标被盖住；由 OverlayLayoutTest 守着。
     */
    val handRanksLeft = Rect(0, 0, 688, 60)
    val handRanksRight = Rect(800, 0, 480, 60)

    /** 左段、右段各放哪些点数（顺序同 [Rank.entries]）。 */
    val handRanksLeftRanks: List<Rank> = Rank.entries.take(8)
    val handRanksRightRanks: List<Rank> = Rank.entries.drop(8)

    /** 合计/边缘张数、加减切换、确认按钮。 */
    val handControl = Rect(396, 150, 160, 180)

    /** 手牌未确认标记、不能确认的原因、确认被拒或停记原因。 */
    val handStatus = Rect(726, 150, 138, 180)

    /** 悬浮窗上所有新增的手牌面板窗口。 */
    val handPanelRects: List<Rect> = listOf(handRanksLeft, handRanksRight, handControl, handStatus)

    /** 手牌未确认时贴在“外面剩余”横条左上方的红色小标签（只画不接触摸），右边界不超过手牌识别区。 */
    val handUnconfirmedTag = Rect(85, 469, 92, 26)

    /** 确认后只留一行小条：放在手牌状态窗里，不再占点数条和按钮的位置。 */
    val handConfirmedChip = Rect(726, 150, 138, 60)
}
