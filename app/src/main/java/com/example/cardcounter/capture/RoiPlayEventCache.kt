package com.example.cardcounter.capture

/** 仅在牌面连续停稳后交出 ROI；交出后锁区，清桌才允许下一手。 */
internal class RoiPlayEventCache<T>(
    private val maxFrames: Int = 8,
    private val clearSamples: Int = 6,
    private val stableSamples: Int = 15,
    /**
     * 清空路径的**后段额外冷却**帧数：在 [clearSamples] 确认离场之后，再多等这么多
     * 连续空帧才算真正复位锁区。
     *
     * 防的是牌桌动画抖动（开点/换手动画里牌闪现→被清掉→同一手牌又出现）：
     * 若清空当拍就复位 reported，同一手牌会被二次 finalize 造成重复入账。
     *
     * 两段是**串联相加**的：清空路径总计需要 `clearSamples + clearedCooldownExtra`
     * 个连续空帧——前段确认离场，后段抑制抖动重入。
     * 默认 3，配合 clearSamples=6 合计 9 帧。
     */
    private val clearedCooldownExtra: Int = 3,
    private val isStill: (T, T) -> Boolean = { _, _ -> false },
    private val discard: (T) -> Unit = {}
) {
    data class Finalized<T>(
        val zone: PlayZone,
        val frames: List<T>,
        val generation: Long
    )

    private data class State<T>(
        val frames: ArrayDeque<T> = ArrayDeque(),
        var emptySamples: Int = 0,
        var stableSamples: Int = 0,
        var primed: Boolean = false,
        var reported: Boolean = false,
        var generation: Long = 0L
    )

    private val states = PlayZone.entries.associateWith { State<T>() }.toMutableMap()

    @Synchronized
    fun reset(generation: Long, primeExistingTable: Boolean) {
        states.values.forEach { state ->
            clear(state)
            state.primed = primeExistingTable
            state.reported = false
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

    @Synchronized
    fun needsFrame(zone: PlayZone, generation: Long): Boolean {
        val state = states.getValue(zone)
        return state.generation == generation && !state.primed && !state.reported
    }

    @Synchronized
    fun observe(
        zone: PlayZone,
        tableVisible: Boolean,
        cardVisible: Boolean,
        frame: T?,
        generation: Long
    ): Finalized<T>? {
        if (!tableVisible) return null
        val state = states.getValue(zone)
        if (state.generation != generation) {
            clear(state)
            state.primed = false
            state.reported = false
            state.generation = generation
        }
        if (cardVisible) {
            state.emptySamples = 0
            if (state.primed || state.reported || frame == null) {
                frame?.let(discard)
                return null
            }
            while (state.frames.size >= maxFrames) discard(state.frames.removeFirst())
            state.frames.addLast(frame)
            state.stableSamples = if (state.frames.size > 1 && isStill(state.frames.elementAt(state.frames.size - 2), frame)) {
                state.stableSamples + 1
            } else 0
            if (state.stableSamples < stableSamples) return null

            val frames = state.frames.toList()
            state.frames.clear()
            state.stableSamples = 0
            state.reported = true
            return Finalized(zone, frames, generation)
        }

        // 两条清空路径的等待长度不同，不要混用：
        //  - 已交出（reported）后等待 clearSamples + clearedCooldownExtra，
        //    后段冷却专门用来抑制「牌面抖动被清掉又冒出来」导致的二次 finalize；
        //  - 仅处于预热（primed，服务重启后桌面残留旧牌）时只需 clearSamples：
        //    预热阶段根本没有交出过牌，没有重复入账风险，多等只会白白拖慢识别恢复。
        val requiredEmpties = if (state.primed) clearSamples else clearSamples + clearedCooldownExtra
        state.emptySamples = (state.emptySamples + 1).coerceAtMost(requiredEmpties)
        if (state.emptySamples < requiredEmpties) return null
        state.stableSamples = 0
        if (state.primed) {
            state.emptySamples = 0
            state.primed = false
            clear(state)
            return null
        }
        state.emptySamples = 0
        if (state.reported) {
            state.reported = false
            return null
        }
        if (state.frames.isEmpty()) return null
        val frames = state.frames.toList()
        state.frames.clear()
        return Finalized(zone, frames, generation)
    }

    private fun clear(state: State<T>) {
        state.frames.forEach(discard)
        state.frames.clear()
        state.emptySamples = 0
        state.stableSamples = 0
    }
}
