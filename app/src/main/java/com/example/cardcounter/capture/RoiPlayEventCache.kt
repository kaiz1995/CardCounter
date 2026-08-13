package com.example.cardcounter.capture

/** 稳定可见即交出 ROI；清桌仍是低置信场景的兜底。 */
internal class RoiPlayEventCache<T>(
    private val maxFrames: Int = 8,
    private val clearSamples: Int = 6,
    private val earlyFrames: Int = 3,
    private val discard: (T) -> Unit = {}
) {
    data class Finalized<T>(
        val zone: PlayZone,
        val frames: List<T>,
        val generation: Long,
        val early: Boolean
    )

    private data class State<T>(
        val frames: ArrayDeque<T> = ArrayDeque(),
        var emptySamples: Int = 0,
        var primed: Boolean = false,
        var earlyInFlight: Boolean = false,
        var reported: Boolean = false,
        var generation: Long = 0L
    )

    private val states = PlayZone.entries.associateWith { State<T>() }.toMutableMap()

    @Synchronized
    fun reset(generation: Long, primeExistingTable: Boolean) {
        states.values.forEach { state ->
            state.frames.forEach(discard)
            state.frames.clear()
            state.emptySamples = 0
            state.primed = primeExistingTable
            state.earlyInFlight = false
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
        return state.generation == generation && !state.primed && !state.earlyInFlight && !state.reported
    }

    /** 提前识别完成后解锁本区；若桌面已清空，下一手可立刻采样。 */
    @Synchronized
    fun completeEarly(zone: PlayZone, generation: Long) {
        val state = states.getValue(zone)
        if (state.generation != generation || !state.earlyInFlight) return
        state.earlyInFlight = false
        if (state.emptySamples >= clearSamples) {
            state.emptySamples = 0
            state.reported = false
        }
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
            state.frames.forEach(discard)
            state.frames.clear()
            state.emptySamples = 0
            state.primed = false
            state.earlyInFlight = false
            state.reported = false
            state.generation = generation
        }
        if (cardVisible) {
            state.emptySamples = 0
            if (!state.primed && !state.earlyInFlight && !state.reported && frame != null) {
                while (state.frames.size >= maxFrames) discard(state.frames.removeFirst())
                state.frames.addLast(frame)
                if (state.frames.size >= earlyFrames) {
                    val frames = state.frames.toList()
                    state.frames.clear()
                    state.earlyInFlight = true
                    return Finalized(zone, frames, generation, early = true)
                }
            } else if (frame != null) {
                discard(frame)
            }
            return null
        }

        state.emptySamples = (state.emptySamples + 1).coerceAtMost(clearSamples)
        if (state.emptySamples < clearSamples) return null
        if (state.primed) {
            state.emptySamples = 0
            state.primed = false
            return null
        }
        if (state.earlyInFlight) return null
        state.emptySamples = 0
        if (state.reported) {
            state.reported = false
            return null
        }
        if (state.frames.isEmpty()) return null
        val frames = state.frames.toList()
        state.frames.clear()
        return Finalized(zone, frames, generation, early = false)
    }
}
