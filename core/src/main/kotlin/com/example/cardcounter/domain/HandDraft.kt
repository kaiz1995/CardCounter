package com.example.cardcounter.domain

/** 确认手牌所需的数据：和 LedgerCommand.ConfirmHand 的 cards / handCount 一一对应。 */
data class HandConfirmData(val cards: RankCounts, val handCount: Int)

/**
 * 手牌草稿：识别结果叠加用户的加减，确认前随时可改。
 * 纯 Kotlin、不可变；识别线程和主线程共享请用 [HandDraftHolder]。
 */
data class HandDraft(
    /** 当前显示的各点数张数（没改过的跟识别走，改过的是用户的值）。 */
    val counts: RankCounts = RankCounts.zero(),
    /** 按牌边缘数出来的张数；null = 还没数出来。 */
    val positionCount: Int? = null,
    /** 用户改过的点数；识别刷新不覆盖这些点数。 */
    val edited: Set<Rank> = emptySet()
) {
    /** 点数合计。 */
    val total: Int get() = counts.total()

    fun isEdited(rank: Rank): Boolean = rank in edited

    /** 数出来的张数和点数合计对不上（已经数出张数才算）；界面据此把两个数标红。 */
    val mismatch: Boolean get() = positionCount != null && total != positionCount

    /** 识别刷新：没改过的点数跟着识别走（不超过库存），改过的点数保持用户的值。 */
    fun refresh(recognized: RankCounts, positionCount: Int?): HandDraft = copy(
        counts = RankCounts.of(
            Rank.entries.associateWith {
                if (it in edited) counts[it] else minOf(recognized[it], Rank.initialInventory[it])
            }
        ),
        positionCount = positionCount
    )

    /** 某个点数加一张或减一张：下限 0，上限该点数库存；没有实际变化不算改过。 */
    fun adjust(rank: Rank, delta: Int): HandDraft {
        val next = (counts[rank] + delta).coerceIn(0, Rank.initialInventory[rank])
        if (next == counts[rank]) return this
        return copy(
            counts = RankCounts.of(counts.asMap() + (rank to next)),
            edited = edited + rank
        )
    }

    /** 不能确认的原因；null 表示可以确认。 */
    val blockReason: String?
        get() {
            val position = positionCount
            return when {
                position == null || position <= 0 -> "还没数出手牌张数，暂时不能确认"
                total != position -> "点数合计 $total 张，数出来 $position 张，必须改到一致才能确认"
                !counts.fitsWithin(Rank.initialInventory) -> "有点数超过库存，必须改回库存以内才能确认"
                else -> null
            }
        }

    val canConfirm: Boolean get() = blockReason == null

    /** 可以确认时返回当时显示的点数和边缘张数，否则 null。 */
    fun confirmData(): HandConfirmData? =
        if (canConfirm) HandConfirmData(counts, requireNotNull(positionCount)) else null
}

/** 识别服务（采集线程）和悬浮窗（主线程）共享的手牌草稿，所有读写串行。 */
class HandDraftHolder {
    private var draft = HandDraft()

    @Synchronized
    fun snapshot(): HandDraft = draft

    @Synchronized
    fun refresh(recognized: RankCounts, positionCount: Int?): HandDraft {
        draft = draft.refresh(recognized, positionCount)
        return draft
    }

    @Synchronized
    fun adjust(rank: Rank, delta: Int): HandDraft {
        draft = draft.adjust(rank, delta)
        return draft
    }

    /** 新局或手牌确认后清空，包括用户改过的标记。 */
    @Synchronized
    fun clear() {
        draft = HandDraft()
    }
}
