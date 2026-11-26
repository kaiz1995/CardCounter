package com.example.cardcounter.domain

/** 一次手牌识别的结果：各点数张数，以及按牌边缘数出来的张数（没数出来为 null）。 */
data class HandReading(val cards: RankCounts, val positionCount: Int?)

/** 锁进账本的手牌：点数向量和数出来的张数。合格的手牌张数一定数出来了，所以不会是 null。 */
data class HandConfirmData(val cards: RankCounts, val handCount: Int)

/**
 * 手牌自动锁定：不再有人工确认，手牌只靠识别。
 *
 * 合格手牌 = 连续 [stableFrames] 次识别结果（点数向量和数出来的张数）完全不变，
 * 且点数合计 = 数出来的张数，且每个点数都不超库存。
 * 取到锁定时刻之前最近一段合格的结果；锁定时刻是出牌阶段开始（开局门因“出牌”圆标打开的那一刻）。
 *
 * 手牌只会在出牌后变少：合格结果如果是已有合格手牌的真子集，说明自己已经出过牌，不能拿来替换。
 * 换牌期（进贡、还贡）的手牌是先少一张再补一张，补回来之后不是真子集，照常替换。
 *
 * [lock] 之后不再接收识别结果，也不允许事后补认。识别线程写、各线程读，方法都加锁。
 */
class HandAutoLock(private val stableFrames: Int = STABLE_FRAMES) {
    private var latest: HandReading? = null
    private var streak = 0
    private var qualified: HandConfirmData? = null
    private var locked = false

    /** 收一次识别结果；已锁定后忽略。 */
    @Synchronized
    fun observe(reading: HandReading) {
        if (locked) return
        streak = if (reading == latest) streak + 1 else 1
        latest = reading
        if (streak < stableFrames || !isQualified(reading)) return
        val position = reading.positionCount ?: return
        val current = qualified
        val playedAway = current != null && reading.cards != current.cards && reading.cards.fitsWithin(current.cards)
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
