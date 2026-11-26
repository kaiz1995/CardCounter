package com.example.cardcounter.capture

/**
 * 每个出牌区的停稳判定：牌面连续静止满 [stableSamples] 次就交出“最后一段连续静止”的帧。
 * 交出后不锁区：画面一旦不静止就算换了画面，再次停稳会再交出一次；同一张画面一直静止只交出一次。
 * 清桌（连续 [clearSamples] 次看不到牌）只清空缓存、解除 primed；清桌时若还没交出，且至少比较到过一次静止（见 [hasUndelivered]），
 * 兜底交出最后一段静止帧。已交出后才来的一帧变化画面（收牌、淡出动画）没有停稳过，清桌时不兜底交出。
 * 没交出的一手后面先来了变化画面（没停稳就被收走），这一手仍留着待兜底，直到新画面自己静止过一次（新牌盖上来）才放弃。
 * 同一手是不是新的一手由 [StablePlayGate] 判断，这里只负责停稳。
 *
 * [isStill] 只能依赖帧里的轻量指纹：帧交出、位图被回收之后，缓存还要拿上一帧继续比较。
 */
class RoiPlayEventCache<T>(
    private val maxFrames: Int = 8,
    private val clearSamples: Int = 6,
    private val stableSamples: Int = 15,
    private val isStill: (T, T) -> Boolean = { _, _ -> false },
    private val discard: (T) -> Unit = {}
) {
    data class Finalized<T>(
        val zone: PlayZone,
        val frames: List<T>,
        val generation: Long
    )

    private class State<T> {
        /** 当前这一段连续静止的帧：起点（最后一个不静止的帧）加上之后的静止帧，最多 maxFrames 帧。 */
        val frames = ArrayDeque<T>()
        /** 静止过一次以上、还没交出就换了画面的上一段：清桌时兜底交出；新画面自己静止过一次就放弃。 */
        val pending = ArrayDeque<T>()
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
     * 这一区有一手牌停稳过一次以上、还没交出，清桌时会兜底交出（见 [observe]）。
     * 轮到他的判断要等这手交出、入账之后，否则兜底入账会把“轮到过”标志清掉。
     */
    @Synchronized
    fun hasUndelivered(zone: PlayZone, generation: Long): Boolean {
        val state = states.getValue(zone)
        return state.generation == generation && fallbackSource(state) != null
    }

    /** 这一区已连续空桌满 [clearSamples] 次完成清桌，且当前没有牌或待兜底的牌。 */
    @Synchronized
    fun isCleared(zone: PlayZone, generation: Long): Boolean {
        val state = states.getValue(zone)
        return state.generation == generation && state.cleared && fallbackSource(state) == null
    }

    @Synchronized
    fun observe(
        zone: PlayZone,
        tableVisible: Boolean,
        cardVisible: Boolean,
        frame: T?,
        generation: Long
    ): Finalized<T>? {
        if (!tableVisible) {
            frame?.let(discard)
            return null
        }
        val state = states.getValue(zone)
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
                // 新画面自己也静止过一次：是新牌盖了上来，之前没停稳的那一手不再兜底。
                dropPending(state)
                while (state.frames.size >= maxFrames) discard(state.frames.removeFirst())
                state.frames.addLast(frame)
                state.stillCount++
                if (state.stillCount < stableSamples) return null
                val frames = state.frames.toList()
                state.frames.clear()
                state.stillCount = 0
                state.delivered = true
                return Finalized(zone, frames, generation)
            }
            // 画面变了或刚出现：新的一段静止从这一帧开始。
            // 上一段静止过还没交出（没停稳就被收走，收牌动画先来一帧）：先留着，清桌时兜底交出。
            if (!state.delivered && state.stillCount > 0) {
                dropPending(state)
                state.pending.addAll(state.frames)
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
        val source = fallbackSource(state)
        val frames = source?.toList().orEmpty()
        source?.clear()
        clear(state)
        state.cleared = true
        state.primed = false
        return if (source != null) Finalized(zone, frames, generation) else null
    }

    /**
     * 清桌时兜底交出的帧：当前这一段至少比较到一次静止就交它，否则交换画面前留下的那一段。
     * 已交出后才来的变化画面（收牌、淡出）、旧牌都不交。
     */
    private fun fallbackSource(state: State<T>): ArrayDeque<T>? = when {
        state.primed || state.delivered -> null
        state.stillCount > 0 -> state.frames
        state.pending.isNotEmpty() -> state.pending
        else -> null
    }

    private fun dropPending(state: State<T>) {
        state.pending.forEach(discard)
        state.pending.clear()
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
