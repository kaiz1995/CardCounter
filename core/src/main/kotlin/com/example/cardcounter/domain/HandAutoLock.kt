package com.example.cardcounter.domain

import com.example.cardcounter.vision.YoloDetection

/**
 * 一次手牌识别的结果：各点数张数，按牌边缘数出来的总张数（没数出来为 null），
 * 以及独立锁定防线校验结果 [structureValid]（分排张数一致且扇形从左到右点数单调不增）。
 */
data class HandReading(
    val cards: RankCounts,
    val positionCount: Int?,
    val structureValid: Boolean
)

/** 锁进账本的手牌：点数向量和数出来的张数。合格的手牌张数一定数出来了，所以不会是 null。 */
data class HandConfirmData(val cards: RankCounts, val handCount: Int)

/**
 * 独立锁定防线：防止模型把某张高牌误识为低牌、凑巧总张数相等而错锁。
 * 1. 分排张数一致：上排检测框数 == 上排边缘计数，且下排检测框数 == 下排边缘计数；
 * 2. 扇形单调不增：上排从左到右接下排从左到右，点数按 [Rank] 从大到小（ordinal 单调不减）。
 */
object HandLockGuard {
    /** 320×320 手牌画布中上排顶外缘下限：手牌真实框中心均 ≥211.9，按钮/倒计时误检集中在 ≤204.2。 */
    const val MIN_HAND_CENTER_Y = 207f

    /** 320×320 手牌画布中上下两排的纵向分界线（上排 ≤224，下排（含选中弹起牌）≥234）。 */
    const val ROW_SPLIT_Y = 229f

    fun filterHandDetections(detections: List<YoloDetection>): List<YoloDetection> =
        detections.filter { it.centerY >= MIN_HAND_CENTER_Y }

    fun isStructureValid(detections: List<YoloDetection>, votedRows: Pair<Int, Int>?): Boolean {
        val (expectedTop, expectedBottom) = votedRows ?: return false
        if (detections.isEmpty()) return false
        val top = detections.filter { it.centerY < ROW_SPLIT_Y }.sortedBy { it.centerX }
        val bottom = detections.filter { it.centerY >= ROW_SPLIT_Y }.sortedBy { it.centerX }
        if (top.size != expectedTop || bottom.size != expectedBottom) return false
        val ordered = top + bottom
        for (i in 0 until ordered.size - 1) {
            if (ordered[i].rank.ordinal > ordered[i + 1].rank.ordinal) return false
        }
        return true
    }

    fun buildReading(
        cards: RankCounts,
        detections: List<YoloDetection>,
        votedRows: Pair<Int, Int>?
    ): HandReading = HandReading(
        cards = cards,
        positionCount = votedRows?.let { it.first + it.second },
        structureValid = isStructureValid(detections, votedRows)
    )
}

/**
 * 手牌自动锁定：不再有人工确认，手牌只靠识别。
 *
 * 合格手牌 = 连续 [stableFrames] 次识别结果（点数向量、边缘张数、独立防线结果）完全不变，
 * 且点数合计 = 数出来的张数，且每个点数都不超库存，且通过独立防线（分排核对 + 从左到右单调不增）。
 * 取到锁定时刻之前最近一段合格的结果；锁定时刻是出牌阶段开始（开局门因“出牌”圆标打开的那一刻）。
 *
 * 手牌只会在出牌后变少：合格结果如果是已有合格手牌的真子集，说明自己已经出过牌，不能拿来替换。
 * 换牌期（进贡、还贡）的手牌是先少一张再补一张，补回来之后不是真子集，照常替换；
 * 若换牌后形成了稳定的新牌型（含旧手牌没有的新点数）却未通过锁定校验，必须作废旧的合格手牌，宁可停记绝不错锁。
 *
 * [lock] 之后不再接收识别结果，也不允许事后补认。识别线程写、各线程读，方法都加锁。
 */
class HandAutoLock(private val stableFrames: Int = STABLE_FRAMES) {
    private var latest: HandReading? = null
    private var streak = 0
    private var qualified: HandConfirmData? = null
    private var locked = false

    /** 收一次识别结果；已锁定后忽略。[selfCardVisible] 表示抓这一帧时自己面前的出牌区（SELF）正摆着牌。 */
    @Synchronized
    fun observe(reading: HandReading, selfCardVisible: Boolean = false) {
        if (locked) return
        streak = if (reading == latest) streak + 1 else 1
        latest = reading
        if (streak < stableFrames) return
        val current = qualified
        if (!isQualified(reading)) {
            if (current != null &&
                reading.positionCount != null &&
                (!reading.cards.fitsWithin(current.cards) ||
                    (!selfCardVisible && reading.positionCount != current.handCount))
            ) {
                qualified = null
            }
            return
        }
        val position = reading.positionCount ?: return
        // 只有自己桌面此时正摆着已出之牌，新合格手牌变成真子集才是“已经首出变少”；桌面空空的变少是还贡回归。
        val playedAway = selfCardVisible &&
            current != null &&
            reading.cards != current.cards &&
            reading.cards.fitsWithin(current.cards)
        if (!playedAway) qualified = HandConfirmData(reading.cards, position)
    }

    /** 最近一次识别结果，只给界面顶部点数条显示；还没识别过为 null。 */
    @Synchronized
    fun latest(): HandReading? = latest

    /** 到锁定时刻：返回最近一段合格的手牌，没有就返回 null（调用方停记）。之后不再变化。 */
    @Synchronized
    fun lock(): HandConfirmData? {
        locked = true
        return qualified
    }

    /** 新局：全部清空。 */
    @Synchronized
    fun reset() {
        latest = null
        streak = 0
        qualified = null
        locked = false
    }

    private fun isQualified(reading: HandReading): Boolean {
        val position = reading.positionCount ?: return false
        return position > 0 &&
            reading.structureValid &&
            reading.cards.total() == position &&
            reading.cards.fitsWithin(Rank.initialInventory)
    }

    companion object {
        /**
         * 连续不变的识别次数。手机每 250ms 识别一次（RANK_PROBE_INTERVAL_MS），4 次约 1 秒：
         * 比单帧抖动长，又远短于换牌期结束到出牌开始之间的间隔。
         */
        const val STABLE_FRAMES = 4
    }
}
