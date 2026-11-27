package com.example.cardcounter.capture

import com.example.cardcounter.domain.RankCounts
import java.util.concurrent.atomic.AtomicBoolean

data class CaptureProbeSnapshot(
    val running: Boolean = false,
    /** 系统停掉了投屏（用户在下拉栏点停止等）：悬浮窗保留并显示“已暂停”，等用户点“继续记牌”。 */
    val pausedBySystem: Boolean = false,
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
        pausedBySystem -> "已暂停：系统停止了截屏，可在悬浮窗点“继续记牌”"
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

    /** 系统停掉了投屏：不再记牌，但保留暂停标记让悬浮窗知道可以继续。 */
    fun systemStopped() {
        current = current.copy(running = false, pausedBySystem = true)
    }

    /** 用户主动停止记牌（或重新开始）时清掉暂停标记。 */
    fun clearPause() {
        current = current.copy(pausedBySystem = false)
    }
}

/** 悬浮窗与主界面只读的手牌/牌桌实时状态；由采集服务更新，新局或停止时清掉。 */
data class OverlayProbeSnapshot(
    val tableVisible: Boolean = false,
    val latestHandCards: RankCounts? = null,
    val handLocked: Boolean = false,
    val handConfirmedAtMs: Long? = null,
    val handConfirmedCount: Int? = null,
    val gameSkipped: Boolean = false
)

object OverlayProbeState {
    private val lock = Any()

    @Volatile
    private var current = OverlayProbeSnapshot()

    @Volatile
    var onChange: (() -> Unit)? = null

    fun snapshot(): OverlayProbeSnapshot = current

    fun update(transform: (OverlayProbeSnapshot) -> OverlayProbeSnapshot) {
        val changed = synchronized(lock) {
            val next = transform(current)
            if (next != current) {
                current = next
                true
            } else {
                false
            }
        }
        if (changed) onChange?.invoke()
    }

    fun notifyChanged() {
        onChange?.invoke()
    }

    fun reset() {
        synchronized(lock) {
            current = OverlayProbeSnapshot()
        }
        onChange?.invoke()
    }
}

internal fun handChangePercent(before: IntArray?, after: IntArray): Double? {
    if (before == null || before.size != after.size) return null
    return before.indices.sumOf { kotlin.math.abs(before[it] - after[it]) }.toDouble() /
        (after.size * 255.0) * 100.0
}

/**
 * 这一帧手牌用哪一路的识别结果；返回 null = 这一帧不刷新草稿。
 * 模型已加载：只认模型，这一帧没结果（出错或 0 个框）就跳过，不拿模板顶替，也不去跑 [template]；
 * 模型没加载：才用模板。数出来的张数不参与挑选。
 */
internal fun chooseHandCards(modelLoaded: Boolean, model: RankCounts?, template: () -> RankCounts): RankCounts? =
    if (modelLoaded) model?.takeIf { it.total() > 0 } else template()

internal class SingleFlightGate {
    private val inFlight = AtomicBoolean(false)

    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

    fun release() {
        inFlight.set(false)
    }
}
