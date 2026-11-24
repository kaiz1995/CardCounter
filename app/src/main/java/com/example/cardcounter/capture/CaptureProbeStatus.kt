package com.example.cardcounter.capture

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

/**
 * 悬浮窗显示哪一路的手牌识别结果：优先点数合计等于边缘张数的那一路（模型优先），
 * 都对不上就用模型的结果，没有模型结果才用模板。只用于显示，不再决定是否锁手牌。
 */
internal fun chooseHandCards(positionCount: Int?, model: RankCounts?, template: RankCounts): RankCounts {
    if (positionCount != null) {
        if (model != null && model.total() == positionCount) return model
        if (template.total() == positionCount) return template
    }
    return model?.takeIf { it.total() > 0 } ?: template
}

internal class SingleFlightGate {
    private val inFlight = AtomicBoolean(false)

    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

    fun release() {
        inFlight.set(false)
    }
}
