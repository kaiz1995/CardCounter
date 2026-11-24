package com.example.cardcounter.domain

/** 手牌面板当前处于哪种状态；悬浮窗据此决定画什么、哪些按钮能点。 */
enum class HandPanelMode {
    /** 手牌未确认，可以加减、确认。 */
    EDITING,

    /** 手牌已确认，只读。 */
    CONFIRMED,

    /** 本局停记（守恒失败等），只显示原因。 */
    PAUSED,

    /** 中途打开，本局不记。 */
    SKIPPED
}

/** 悬浮窗手牌面板和“外面剩余”横条要显示的内容，由账本状态和手牌草稿算出，不含任何安卓依赖。 */
data class HandPanelStatus(
    val mode: HandPanelMode,
    /** 横条要醒目标“手牌未确认”：本局正在记，但手牌还没确认。 */
    val handUnconfirmed: Boolean,
    /** 面板上显示的各点数张数：编辑中是草稿，已确认或停记时是已确认手牌。 */
    val shownCounts: RankCounts,
    val handTotal: Int,
    /** 按牌边缘数出来的张数；null = 还没数出来。 */
    val positionCount: Int?,
    /** 点数合计和边缘张数对不上，两个数标红。 */
    val mismatch: Boolean,
    val canEdit: Boolean,
    val canConfirm: Boolean,
    /** 提示：不能确认的原因、确认被拒的原因、停记原因；没有就是 null。 */
    val message: String?
)

/**
 * 面板状态的唯一出处。
 * 优先级：中途打开不记 > 停记 > 已确认 > 编辑中。
 * [rejectReason] 是上一次确认被拒的原因，手牌保持未确认，用户改动后由调用方清掉。
 */
fun handPanelStatus(
    state: GameState,
    draft: HandDraft,
    rejectReason: String?,
    skipped: Boolean
): HandPanelStatus {
    val confirmedHand = state.selfHand
    return when {
        skipped -> HandPanelStatus(
            mode = HandPanelMode.SKIPPED,
            handUnconfirmed = false,
            shownCounts = RankCounts.zero(),
            handTotal = 0,
            positionCount = null,
            mismatch = false,
            canEdit = false,
            canConfirm = false,
            message = "中途打开，这一局不记，等下一局"
        )
        state.pauseReason != null -> HandPanelStatus(
            mode = HandPanelMode.PAUSED,
            handUnconfirmed = confirmedHand == null,
            shownCounts = confirmedHand ?: draft.counts,
            handTotal = (confirmedHand ?: draft.counts).total(),
            positionCount = draft.positionCount,
            mismatch = false,
            canEdit = false,
            canConfirm = false,
            message = "已停记：${state.pauseReason.displayName}"
        )
        confirmedHand != null -> HandPanelStatus(
            mode = HandPanelMode.CONFIRMED,
            handUnconfirmed = false,
            shownCounts = confirmedHand,
            handTotal = confirmedHand.total(),
            positionCount = confirmedHand.total(),
            mismatch = false,
            canEdit = false,
            canConfirm = false,
            message = null
        )
        else -> HandPanelStatus(
            mode = HandPanelMode.EDITING,
            handUnconfirmed = true,
            shownCounts = draft.counts,
            handTotal = draft.total,
            positionCount = draft.positionCount,
            mismatch = draft.mismatch,
            canEdit = true,
            canConfirm = draft.canConfirm,
            message = rejectReason ?: draft.blockReason
        )
    }
}
