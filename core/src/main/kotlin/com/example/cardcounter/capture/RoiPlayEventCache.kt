package com.example.cardcounter.capture

/** 仅在牌面连续停稳后交出 ROI；交出后锁区，清桌才允许下一手。 */
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

        state.emptySamples = (state.emptySamples + 1).coerceAtMost(clearSamples)
        if (state.emptySamples < clearSamples) return null
        state.emptySamples = 0
        state.stableSamples = 0
        if (state.primed) {
            state.primed = false
            clear(state)
            return null
        }
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
