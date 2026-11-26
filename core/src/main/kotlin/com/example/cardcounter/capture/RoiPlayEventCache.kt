package com.example.cardcounter.capture

/** 一手牌是怎么交出来的：写进手机 ROI_FINALIZED 日志、诊断记录和回放审计。 */
enum class FinalizeReason {
    /** 牌面连续静止满 stableSamples 次。 */
    STABLE,

    /** 没停稳的一手被新牌直接盖上，新牌自己静止了一次，而这一手被盖前已经静止够久。 */
    COVERED,

    /** 清桌（连续看不到牌）时兜底交出。 */
    CLEARED,

    /** 整张牌桌不可见（被弹窗、结算画面挡住）时，这一区没交出的一手在这一刻交出。 */
    TABLE_HIDDEN
}

/**
 * 每个出牌区的停稳判定：牌面连续静止满 [stableSamples] 次就交出“最后一段连续静止”的帧。
 * 交出后不锁区：画面一旦不静止就算换了画面，再次停稳会再交出一次；同一张画面一直静止只交出一次。
 *
 * 没停稳的一手有三条路也要交出，不能丢（被盖和牌桌被挡要求这一手至少静止过 [coveredStillSamples] 次，
 * 滑入动画偶尔静止一两次的不算；清桌只要静止过 1 次）：
 * - 被盖：后面先来了变化画面（新牌直接盖在原位，桌面没空过），新画面自己静止一次，这一手就交出；
 * - 牌桌被挡：整张牌桌第一次变得不可见，这一区没交出的一手立刻交出（同一 generation 内）；
 * - 清桌（连续 [clearSamples] 次看不到牌）：只要静止过一次就兜底交出，见 [hasUndelivered]。
 * 已交出后才来的一帧变化画面（收牌、淡出）没停稳过，不兜底交出。
 * 牌桌被短暂挡住、回来后同一手再停稳会再交出一次，是不是重复由 [StablePlayGate] 判断，这里只负责停稳。
 *
 * [isStill] 只能依赖帧里的轻量指纹：帧交出、位图被回收之后，缓存还要拿上一帧继续比较。
 */
class RoiPlayEventCache<T>(
    private val maxFrames: Int = 8,
    private val clearSamples: Int = 6,
    private val stableSamples: Int = 15,
    /** 按采样节拍定：手机 50ms 取 3 次（约 150ms），回放约 97ms 取 2 次（约 194ms）。 */
    private val coveredStillSamples: Int = 3,
    private val isStill: (T, T) -> Boolean = { _, _ -> false },
    private val discard: (T) -> Unit = {}
) {
    data class Finalized<T>(
        val zone: PlayZone,
        val frames: List<T>,
        val generation: Long,
        val reason: FinalizeReason
    )

    private class State<T> {
        /** 当前这一段连续静止的帧：起点（最后一个不静止的帧）加上之后的静止帧，最多 maxFrames 帧。 */
        val frames = ArrayDeque<T>()

        /** 静止过一次以上、还没交出就换了画面的上一段：新画面自己静止时，够久就被盖交出，否则放弃；清桌时兜底交出。 */
        val pending = ArrayDeque<T>()

        /** [pending] 那一段静止过多少次。 */
        var pendingStill = 0
        var last: T? = null
        var emptySamples = 0
        var stillCount = 0
        var primed = false
        var delivered = false
        var cleared = false
        var generation = 0L
    }

    private val states = PlayZone.entries.associateWith { State<T>() }

    @Synchronized
    fun reset(generation: Long, primeExistingTable: Boolean) {
        states.values.forEach { state ->
            clear(state)
            state.primed = primeExistingTable
            state.generation = generation
        }
    }

    /** 服务重启后未回到牌桌，说明没有需要去重的旧桌面牌。 */
    @Synchronized
    fun releasePrime(generation: Long) {
        states.values.filter { it.generation == generation }.forEach { state ->
            state.primed = false
            state.emptySamples = 0
        }
    }

    /** 这一区需要送一帧进来：旧牌（primed）不记，不需要。 */
    @Synchronized
    fun needsFrame(zone: PlayZone, generation: Long): Boolean {
        val state = states.getValue(zone)
        return state.generation == generation && !state.primed
    }

    /**
     * 已经交出、且 [frame] 与上一帧相比画面没变：这一帧只需要指纹，不必再截图送识别。
     * 画面变了（不静止）就必须带上位图，它是下一段静止的起点。
     */
    @Synchronized
    fun isRepeat(zone: PlayZone, generation: Long, frame: T): Boolean {
        val state = states.getValue(zone)
        val last = state.last
        return state.generation == generation && !state.primed && state.delivered &&
            last != null && isStill(last, frame)
    }

    /**
     * 这一区有一手牌静止过一次以上、还没交出，清桌时会兜底交出（见 [observe]）。
     * 轮到他的判断要等这手交出、入账之后，否则兜底入账会把“轮到过”标志清掉。
     */
    @Synchronized
    fun hasUndelivered(zone: PlayZone, generation: Long): Boolean {
        val state = states.getValue(zone)
        return state.generation == generation && fallbackSource(state, minStill = 1) != null
    }

    /** 这一区已连续空桌满 [clearSamples] 次完成清桌，且当前没有牌或待兜底的牌。 */
    @Synchronized
    fun isCleared(zone: PlayZone, generation: Long): Boolean {
        val state = states.getValue(zone)
        return state.generation == generation && state.cleared && fallbackSource(state, minStill = 1) == null
    }

    @Synchronized
    fun observe(
        zone: PlayZone,
        tableVisible: Boolean,
        cardVisible: Boolean,
        frame: T?,
        generation: Long
    ): Finalized<T>? {
        val state = states.getValue(zone)
        if (!tableVisible) {
            frame?.let(discard)
            // 牌桌被挡住：这一区没交出的一手现在就交出，否则局终被挡住就整手丢了。
            // 已交出的、没静止够的、旧牌（primed）、别的 generation 都不交；没东西可交时缓存原样保留。
            if (state.generation != generation) return null
            if (fallbackSource(state, coveredStillSamples) == null) return null
            return takeFallback(zone, state, generation, FinalizeReason.TABLE_HIDDEN, coveredStillSamples)
        }
        if (state.generation != generation) {
            clear(state)
            state.primed = false
            state.generation = generation
        }
        if (cardVisible) {
            state.emptySamples = 0
            state.cleared = false
            if (state.primed || frame == null) {
                frame?.let(discard)
                return null
            }
            val previous = state.last
            state.last = frame
            if (previous != null && isStill(previous, frame)) {
                if (state.delivered) {
                    discard(frame)
                    return null
                }
                // 新画面自己也静止过一次：是新牌盖了上来。之前没停稳的那一手，静止够久就交出，否则当滑入动画放弃。
                val covered = takeCovered(zone, state, generation)
                while (state.frames.size >= maxFrames) discard(state.frames.removeFirst())
                state.frames.addLast(frame)
                state.stillCount++
                if (covered != null) return covered
                if (state.stillCount < stableSamples) return null
                val frames = state.frames.toList()
                state.frames.clear()
                state.stillCount = 0
                state.delivered = true
                return Finalized(zone, frames, generation, FinalizeReason.STABLE)
            }
            // 画面变了或刚出现：新的一段静止从这一帧开始。
            // 上一段静止过还没交出（没停稳就被收走或被盖住）：先留着，新画面静止时判断是盖交出还是放弃，清桌时兜底交出。
            if (!state.delivered && state.stillCount > 0) {
                dropPending(state)
                state.pending.addAll(state.frames)
                state.pendingStill = state.stillCount
            } else {
                state.frames.forEach(discard)
            }
            state.frames.clear()
            state.frames.addLast(frame)
            state.stillCount = 0
            state.delivered = false
            return null
        }

        // 看不到牌：连续性断开，之后再出现的画面重新从起点算。
        state.last = null
        state.emptySamples = (state.emptySamples + 1).coerceAtMost(clearSamples)
        if (state.emptySamples < clearSamples) return null
        val finalized = takeFallback(zone, state, generation, FinalizeReason.CLEARED, minStill = 1)
        state.cleared = true
        state.primed = false
        return finalized
    }

    /**
     * 兜底交出的帧：当前这一段静止够 [minStill] 次就交它；当前这一段还没静止过（刚换的画面）时，
     * 交换画面前留下的那一段（同样要静止够 [minStill] 次）。已交出、旧牌都不交。
     */
    private fun fallbackSource(state: State<T>, minStill: Int): ArrayDeque<T>? = when {
        state.primed || state.delivered -> null
        state.stillCount > 0 -> state.frames.takeIf { state.stillCount >= minStill }
        state.pending.isNotEmpty() -> state.pending.takeIf { state.pendingStill >= minStill }
        else -> null
    }

    /** 交出兜底的帧并清空这一区；没有可交的也清空，由调用方决定要不要先判断。 */
    private fun takeFallback(
        zone: PlayZone,
        state: State<T>,
        generation: Long,
        reason: FinalizeReason,
        minStill: Int
    ): Finalized<T>? {
        val source = fallbackSource(state, minStill)
        val frames = source?.toList().orEmpty()
        source?.clear()
        clear(state)
        return if (source != null) Finalized(zone, frames, generation, reason) else null
    }

    /** 新画面第一次静止：留着的上一段静止够久就作为一手交出，不够就丢掉。 */
    private fun takeCovered(zone: PlayZone, state: State<T>, generation: Long): Finalized<T>? {
        if (state.pending.isEmpty()) return null
        if (state.pendingStill < coveredStillSamples) {
            dropPending(state)
            return null
        }
        val frames = state.pending.toList()
        state.pending.clear()
        state.pendingStill = 0
        return Finalized(zone, frames, generation, FinalizeReason.COVERED)
    }

    private fun dropPending(state: State<T>) {
        state.pending.forEach(discard)
        state.pending.clear()
        state.pendingStill = 0
    }

    private fun clear(state: State<T>) {
        state.frames.forEach(discard)
        state.frames.clear()
        dropPending(state)
        state.last = null
        state.emptySamples = 0
        state.stillCount = 0
        state.delivered = false
        state.cleared = false
    }
}
