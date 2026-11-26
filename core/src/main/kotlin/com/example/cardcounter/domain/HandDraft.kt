package com.example.cardcounter.domain

import kotlin.math.abs

/** 确认手牌所需的数据：和 LedgerCommand.ConfirmHand 的 cards / handCount 一一对应；handCount 没数出来为 null。 */
data class HandConfirmData(val cards: RankCounts, val handCount: Int?)

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
    val edited: Set<Rank> = emptySet(),
    /** 最近一次识别刷新给的点数（原样，不截断）；用户把点数改回这个值，就等于没改过。 */
    val recognized: RankCounts = RankCounts.zero(),
    /** 草稿内容（点数、张数、改过标记）每变一次加一；确认时据此判断用户看到的那一版有没有被后台刷新换掉。 */
    val version: Long = 0
) {
    /** 点数合计。 */
    val total: Int get() = counts.total()

    fun isEdited(rank: Rank): Boolean = rank in edited

    /** 数出来的张数和点数合计对不上（已经数出张数才算）；界面据此把两个数标红。 */
    val mismatch: Boolean get() = positionCount != null && positionCount > 0 && total != positionCount

    /**
     * 识别刷新：没改过的点数跟着识别走，改过的点数保持用户的值。
     * 识别值超过库存时原样保留，由 [blockReason] 拦下，不悄悄截断；
     * 识别值追上了用户改的值，该点数就不再算改过。内容没变化时不涨 [version]。
     */
    fun refresh(recognized: RankCounts, positionCount: Int?): HandDraft {
        val stillEdited = edited.filterTo(HashSet()) { counts[it] != recognized[it] }
        val nextCounts = RankCounts.of(
            Rank.entries.associateWith { if (it in stillEdited) counts[it] else recognized[it] }
        )
        val unchanged = nextCounts == counts && positionCount == this.positionCount && stillEdited == edited
        return if (unchanged) {
            copy(recognized = recognized)
        } else {
            copy(
                counts = nextCounts,
                positionCount = positionCount,
                edited = stillEdited,
                recognized = recognized,
                version = version + 1
            )
        }
    }

    /**
     * 某个点数加一张或减一张：下限 0，上限该点数库存（已经超库存的只能往下减）；没有实际变化不算改过。
     * 改到和当前识别值一致，该点数移出 edited，避免误触 +1 再 -1 后永远绕过张数校验。
     */
    fun adjust(rank: Rank, delta: Int): HandDraft {
        val cap = maxOf(Rank.initialInventory[rank], counts[rank])
        val next = (counts[rank] + delta).coerceIn(0, cap)
        if (next == counts[rank]) return this
        return copy(
            counts = RankCounts.of(counts.asMap() + (rank to next)),
            edited = if (next == recognized[rank]) edited - rank else edited + rank,
            version = version + 1
        )
    }

    /**
     * 不能确认的原因；null 表示可以确认。
     * 用户改过点数后以改后的合计为准：和数出来的张数差 1 张以内只提示（[warning]），差 [EDITED_GAP_LIMIT] 张及以上要拦下核对；
     * 没改过时要求合计等于数出来的张数。
     */
    val blockReason: String?
        get() {
            val position = positionCount
            return when {
                !counts.fitsWithin(Rank.initialInventory) -> "有点数超过库存，必须改回库存以内才能确认"
                edited.isNotEmpty() -> when {
                    total <= 0 -> "点数合计为 0，不能确认"
                    position != null && position > 0 && abs(total - position) >= EDITED_GAP_LIMIT ->
                        "点数合计 $total 张，数出来 $position 张，差得太多；请再核对点数，对上后就能确认"
                    else -> null
                }
                position == null || position <= 0 -> "还没数出手牌张数，暂时不能确认"
                total != position ->
                    "点数合计 $total 张，数出来 $position 张，对不上；核对后可以点点数加减改正，改完就能确认"
                else -> null
            }
        }

    /** 只提示不拦：改过点数、数出来了张数、但和点数合计不一样（差距在 [EDITED_GAP_LIMIT] 以内）。 */
    val warning: String?
        get() {
            val position = positionCount
            return if (blockReason == null && edited.isNotEmpty() && position != null && position > 0 && total != position) {
                "数出来 $position 张，和点数合计 $total 张不一样，核对没错就直接确认"
            } else null
        }

    val canConfirm: Boolean get() = blockReason == null

    /** 可以确认时返回当时显示的点数和数出来的张数（没数出来为 null），否则 null。 */
    fun confirmData(): HandConfirmData? =
        if (canConfirm) HandConfirmData(counts, positionCount?.takeIf { it > 0 }) else null

    companion object {
        /** 用户改过点数后，合计和数出来的张数相差到这个数就不能一键放行。 */
        const val EDITED_GAP_LIMIT = 2
    }
}

/** 点“确认手牌”时的判定结果。 */
sealed interface HandConfirmGate {
    /** 点击之后草稿又变了（后台识别刷新或用户又改了）：不确认，让用户重新核对。 */
    data object Stale : HandConfirmGate

    /** 当前这一版不能确认（见 [HandDraft.blockReason]）。 */
    data object Blocked : HandConfirmGate

    /** 就是用户点击时看到的那一版，可以确认。 */
    data class Ready(val data: HandConfirmData) : HandConfirmGate
}

/**
 * 所见即所确认：[clickedVersion] 是用户点“确认”那一刻草稿的版本号。
 * 现在的版本和它不一样，说明点击到处理之间草稿被换过，这时不能把没看过的那一版记进账本。
 */
fun HandDraft.gate(clickedVersion: Long): HandConfirmGate = when {
    version != clickedVersion -> HandConfirmGate.Stale
    else -> confirmData()?.let { HandConfirmGate.Ready(it) } ?: HandConfirmGate.Blocked
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

    /** 新局或手牌确认后清空，包括用户改过的标记；版本号继续往上走，旧版本的确认请求不会误中新草稿。 */
    @Synchronized
    fun clear() {
        draft = HandDraft(version = draft.version + 1)
    }
}
