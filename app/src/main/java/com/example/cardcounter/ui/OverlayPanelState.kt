package com.example.cardcounter.ui

/** 小图标的四种外观：自己手动收起与自动收起、系统停掉截屏后的暂停，各用不同的描边色区分。 */
enum class OverlayIconState { EXPANDED, COLLAPSED, COLLAPSED_AUTO, PAUSED }

/** 小图标被点一下之后该做什么：面板已经变了，或者只是弹出/收起小菜单。 */
enum class IconTapAction { PANEL_CHANGED, TOGGLE_MENU }

/** [OverlayPanelState.update] 要求悬浮窗服务对“离开牌桌 3 秒收起”计时器做的事。 */
enum class LeaveTableTimer { NONE, START, CANCEL }

/**
 * 悬浮窗记牌面板的展开/收起状态机，纯逻辑、不碰 Android，方便单元测试。
 *
 * 规则：
 * - 横屏、截屏运行中、牌桌可见时自动展开；牌桌不可见满 3 秒（计时由服务负责）后自动收起；
 * - 自动收起后点小图标直接展开（[pinnedOpen]），之后横屏期间不再自动收起，
 *   直到用户再点一次收起，或转竖屏、记牌器主界面回到前台、停止记牌；
 * - 在牌桌上手动收起后，回到牌桌（或再点一次）会重新展开。
 */
class OverlayPanelState {
    /** 牌桌可见（离开后已去抖 3 秒）。 */
    var effectiveOnTable: Boolean = false
        private set

    /** 用户在横屏里手动收起了面板。 */
    var manualCollapsed: Boolean = false
        private set

    /** 用户手动展开：横屏期间一直保持展开，不受牌桌可见性影响。 */
    var pinnedOpen: Boolean = false
        private set

    /** 离开牌桌计时器是否在走。 */
    var leaveTablePending: Boolean = false
        private set

    fun showExpanded(): Boolean = pinnedOpen || (effectiveOnTable && !manualCollapsed)

    /** 每次重绘前调用；返回值告诉服务要不要启动/取消离开牌桌计时器。 */
    fun update(
        mainForeground: Boolean,
        landscape: Boolean,
        captureRunning: Boolean,
        tableVisible: Boolean
    ): LeaveTableTimer {
        if (mainForeground || !landscape || !captureRunning) {
            effectiveOnTable = false
            manualCollapsed = false
            pinnedOpen = false
            return cancelTimer()
        }
        if (tableVisible) {
            val timer = cancelTimer()
            if (!effectiveOnTable) {
                effectiveOnTable = true
                manualCollapsed = false
            }
            return timer
        }
        if (effectiveOnTable && !leaveTablePending) {
            leaveTablePending = true
            return LeaveTableTimer.START
        }
        return LeaveTableTimer.NONE
    }

    /** 离开牌桌满 3 秒：自动收起；用户手动展开过的面板不受影响（[pinnedOpen]）。 */
    fun onLeaveTableTimeout() {
        leaveTablePending = false
        effectiveOnTable = false
        manualCollapsed = false
    }

    /** 小图标被点一下。横屏且截屏在运行时直接展开/收起面板，否则只切换小菜单。 */
    fun onIconTap(landscape: Boolean, captureRunning: Boolean): IconTapAction {
        if (!landscape || !captureRunning) return IconTapAction.TOGGLE_MENU
        if (showExpanded()) {
            pinnedOpen = false
            manualCollapsed = true
        } else if (effectiveOnTable) {
            // 在牌桌上自己收起过：再点一次只是取消手动收起，不钉住。
            manualCollapsed = false
        } else {
            // 自动收起后（离开牌桌）点图标：钉住展开，横屏期间不再自动收起。
            pinnedOpen = true
            manualCollapsed = false
        }
        return IconTapAction.PANEL_CHANGED
    }

    fun iconState(landscape: Boolean, captureRunning: Boolean, pausedBySystem: Boolean): OverlayIconState = when {
        pausedBySystem && !captureRunning -> OverlayIconState.PAUSED
        showExpanded() -> OverlayIconState.EXPANDED
        manualCollapsed -> OverlayIconState.COLLAPSED
        landscape && captureRunning -> OverlayIconState.COLLAPSED_AUTO
        else -> OverlayIconState.COLLAPSED
    }

    private fun cancelTimer(): LeaveTableTimer {
        if (!leaveTablePending) return LeaveTableTimer.NONE
        leaveTablePending = false
        return LeaveTableTimer.CANCEL
    }
}
