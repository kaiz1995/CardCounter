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
    /** 提示：不能确认的原因、确认被拒的原因、停记原因、可以确认但张数对不上的提醒（draft.warning）；没有就是 null。 */
    val message: String?,
    /** 停记时是否可以撤销上一手外面出牌。 */
    val canRevertLastOutsidePlay: Boolean = false
)

/**
 * 一次确认没成的提示（被拒、草稿刚刷新等）。只对当时的草稿版本有效：草稿一变（后台识别刷新或用户改动）就作废，
 * 不会盖住新草稿自己的原因。
 */
data class RejectNote(val text: String, val draftVersion: Long) {
    fun visibleFor(draft: HandDraft): String? = text.takeIf { draft.version == draftVersion }
}

/**
 * 面板状态的唯一出处。
 * 优先级：中途打开不记 > 停记 > 已确认 > 编辑中。
 * 编辑中的提示：草稿自己不能确认的原因（[HandDraft.blockReason]）优先，其次是 [rejectNote]（仍对当前草稿版本有效时），最后是草稿的提醒。
 */
fun handPanelStatus(
    state: GameState,
    draft: HandDraft,
    rejectNote: RejectNote?,
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
            message = "已停记：${state.pauseReason.displayName}",
            canRevertLastOutsidePlay = state.lastUnrevertedOutsidePlay() != null
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
            message = draft.blockReason ?: rejectNote?.visibleFor(draft) ?: draft.warning
        )
    }
}

/** 停记面板上的按钮。 */
enum class PausedAction {
    /** 只恢复记牌，不撤销。 */
    RESUME,

    /** 先撤销最近一手外面出牌再恢复。 */
    REVERT_AND_RESUME
}

/**
 * 停记时面板上要放的按钮，从上到下的顺序。永远有 [PausedAction.RESUME]：确认手牌引起的停记，
 * 外面刚记的出牌是对的，只能继续、不能撤；有可撤的外面出牌时再多一个 [PausedAction.REVERT_AND_RESUME]。
 * 不是停记状态返回空列表。
 */
fun pausedActions(status: HandPanelStatus): List<PausedAction> = when {
    status.mode != HandPanelMode.PAUSED -> emptyList()
    status.canRevertLastOutsidePlay -> listOf(PausedAction.REVERT_AND_RESUME, PausedAction.RESUME)
    else -> listOf(PausedAction.RESUME)
}
