package com.example.cardcounter.capture

import com.example.cardcounter.domain.RankCounts

/**
 * 协调识别线程（rankThread）上的出牌去重门、开局门与代数（generation）门控：
 * 所有对 [openingPlayGate] 和 [stablePlayGate] 的读写与新局重置都经 [enqueueRank] 串行执行，
 * 并在改状态前复核代数，防止旧局信号或延后交出的旧局牌写入新局状态。
 */
class RankGateCoordinator(
    val stablePlayGate: StablePlayGate = StablePlayGate(),
    val openingPlayGate: OpeningPlayGate = OpeningPlayGate(),
    private val enqueueRank: (() -> Unit) -> Boolean
) {
    @Volatile
    var generation: Long = 0L
        private set

    /** 推进并返回新一代代数；与调用方的会话状态重置共用同步调用时可保证严格递增。 */
    @Synchronized
    fun nextGeneration(): Long {
        generation += 1L
        return generation
    }

    /** 把开局门、去重门与识别线程本地状态重置投递到识别线程串行执行。 */
    fun postReset(
        expectedGeneration: Long,
        alreadyInPlay: Boolean,
        extraReset: () -> Unit = {}
    ): Boolean = enqueueRank {
        if (expectedGeneration != generation) return@enqueueRank
        openingPlayGate.reset(alreadyInPlay = alreadyInPlay)
        stablePlayGate.reset()
        extraReset()
    }

    /** 在识别线程上把旧信号交给去重门与开局门；执行到改状态前必须确认仍是当前代。 */
    fun postZoneSignals(
        expectedGeneration: Long,
        zone: PlayZone,
        signals: ZoneSignals,
        nowMs: Long,
        isOnTable: (PlayZone) -> Boolean,
        onOpened: () -> Unit,
        onReplay: (List<OpeningPlayGate.SkippedPlay>) -> Unit
    ): Boolean = enqueueRank {
        if (expectedGeneration != generation) return@enqueueRank
        val wasReady = openingPlayGate.isReady()
        val retries = routeZoneSignals(zone, signals, nowMs, stablePlayGate, openingPlayGate, isOnTable)
        if (expectedGeneration != generation) return@enqueueRank
        if (!wasReady && openingPlayGate.isReady()) {
            onOpened()
        }
        if (expectedGeneration != generation) return@enqueueRank
        onReplay(retries)
    }

    /**
     * 在识别线程上处理完成推理且通过规则检查的一手牌：
     * 写 [OpeningPlayGate.notePrePlaySkipped] 或放行入账前均复核代数，防止旧局事件污染新局。
     */
    fun handleFinalizedPlay(
        expectedGeneration: Long,
        zone: PlayZone,
        cards: RankCounts,
        sampleTimeMs: Long,
        onPrePlaySkipped: () -> Unit,
        onAdmit: () -> Unit
    ) {
        if (expectedGeneration != generation) return
        if (!openingPlayGate.isReady()) {
            openingPlayGate.notePrePlaySkipped(zone, cards, sampleTimeMs)
            onPrePlaySkipped()
            return
        }
        if (expectedGeneration != generation) return
        onAdmit()
    }
}
