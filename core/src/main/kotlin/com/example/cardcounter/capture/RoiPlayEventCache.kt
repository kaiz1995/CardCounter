package com.example.cardcounter.capture

/**
 * 每个出牌区的停稳判定：牌面连续静止满 [stableSamples] 次就交出“最后一段连续静止”的帧。
 * 交出后不锁区：画面一旦不静止就算换了画面，再次停稳会再交出一次；同一张画面一直静止只交出一次。
 * 清桌（连续 [clearSamples] 次看不到牌）只清空缓存、解除 primed；清桌时若还没交出，兜底交出最后一段静止帧。
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
        var last: T? = null
        var emptySamples = 0
        var stillCount = 0
        var primed = false
        var delivered = false
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
            state.frames.forEach(discard)
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
        val fallback = !state.primed && !state.delivered && state.frames.isNotEmpty()
        val frames = if (fallback) state.frames.toList() else emptyList()
        if (fallback) state.frames.clear() else clear(state)
        state.emptySamples = 0
        state.stillCount = 0
        state.delivered = false
        state.primed = false
        return if (fallback) Finalized(zone, frames, generation) else null
    }

    private fun clear(state: State<T>) {
        state.frames.forEach(discard)
        state.frames.clear()
        state.last = null
        state.emptySamples = 0
        state.stillCount = 0
        state.delivered = false
    }
}
