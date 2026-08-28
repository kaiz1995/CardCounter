package com.example.cardcounter.capture

import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import java.util.concurrent.atomic.AtomicBoolean

data class CaptureProbeSnapshot(
    val running: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
    val frameCount: Long = 0,
    val framesPerSecond: Double? = null,
    val handChangePercent: Double? = null,
    val maxHandChangePercent: Double? = null,
    val stableHandSamples: Int = 0,
    val error: String? = null
) {
    fun summary(): String = when {
        error != null -> "失败：$error"
        running && width != null && height != null && framesPerSecond != null ->
            "运行中：${width}×${height}，${"%.1f".format(framesPerSecond)} fps，$frameCount 帧" +
                handChangePercent?.let {
                    "；手牌候选变化 ${"%.1f".format(it)}%，本次最大 ${"%.1f".format(maxHandChangePercent)}%，稳定样本 $stableHandSamples"
                }.orEmpty()
        running -> "启动中：等待首帧"
        else -> "未启动"
    }
}

/** 仅保留当前探针诊断，不保存任何屏幕帧。 */
object CaptureProbeStatus {
    @Volatile
    private var current = CaptureProbeSnapshot()

    fun snapshot(): CaptureProbeSnapshot = current

    fun started() {
        current = CaptureProbeSnapshot(running = true)
    }

    fun frame(width: Int, height: Int, frameCount: Long, framesPerSecond: Double) {
        current = current.copy(
            running = true,
            width = width,
            height = height,
            frameCount = frameCount,
            framesPerSecond = framesPerSecond,
            error = null
        )
    }

    fun handCandidate(changePercent: Double?, maxChangePercent: Double, stableSamples: Int) {
        current = current.copy(
            handChangePercent = changePercent,
            maxHandChangePercent = maxChangePercent,
            stableHandSamples = stableSamples
        )
    }

    fun failed(message: String) {
        current = current.copy(running = false, error = message)
    }

    fun stopped() {
        current = current.copy(running = false)
    }
}

internal fun handChangePercent(before: IntArray?, after: IntArray): Double? {
    if (before == null || before.size != after.size) return null
    return before.indices.sumOf { kotlin.math.abs(before[it] - after[it]) }.toDouble() /
        (after.size * 255.0) * 100.0
}

internal fun stableRankCandidateCount(
    previous: RankCounts?,
    stableCount: Int,
    next: RankCounts
): Int = when {
    next.total() == 0 -> 0
    next == previous -> stableCount + 1
    else -> 1
}

/** 手牌快照门槛：只要求非空且在库存内，接受任意张数（不再要求与位置检测一致或 33 张）。 */
internal fun isValidBaseline(positionCount: Int?, cards: RankCounts): Boolean =
    cards.total() > 0 && cards.fitsWithin(Rank.initialInventory)

internal fun shouldInvalidatePrePlayHand(
    firstPlayArmed: Boolean,
    hasStableHand: Boolean,
    handChanged: Boolean
): Boolean = handChanged && !firstPlayArmed && !hasStableHand

internal class SingleFlightGate {
    private val inFlight = AtomicBoolean(false)

    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

    fun release() {
        inFlight.set(false)
    }
}

internal data class OpeningSignals(
    val controlsVisible: Boolean,
    val centerCardsVisible: Boolean
)

internal enum class FirstPlayBoundary {
    NONE,
    LOCK
}

internal class FirstPlayBoundaryGate {
    private var readySamples = 0
    private var fired = false

    fun update(signals: OpeningSignals, hasStableHand: Boolean): FirstPlayBoundary {
        if (fired) return FirstPlayBoundary.NONE
        readySamples = if (hasStableHand) readySamples + 1 else 0
        if (readySamples < 2) return FirstPlayBoundary.NONE
        fired = true
        return FirstPlayBoundary.LOCK
    }

    fun reset() {
        readySamples = 0
        fired = false
    }
}
