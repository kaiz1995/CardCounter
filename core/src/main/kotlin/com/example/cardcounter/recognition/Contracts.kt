package com.example.cardcounter.recognition

import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.storage.LedgerCommand

/*
 * 识别链路各块之间的接口。手机（:app）与电脑离线评测（:eval）都只经过这里，
 * 所以两边跑的是同一套状态机和账本代码；只有最外层“帧从哪来、检测器用哪个”不同。
 * 改这里的签名要同时改所有实现方，并在 docs/rebuild/DATA-FORMATS.md 记录。
 */

/** RGB 交错像素，长度 = width * height * 3。 */
class RgbImage(val width: Int, val height: Int, val rgb: ByteArray) {
    init {
        require(width > 0 && height > 0 && rgb.size == width * height * 3) {
            "RgbImage 尺寸不符：${width}x$height，字节 ${rgb.size}"
        }
    }
}

/** 检测框：坐标一律是相对 320×320 模型输入画布的 0..1 小数。 */
data class Detection(
    val rank: Rank,
    val confidence: Float,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float
)

enum class DetectorBackend { ONNX_CPU, OFFLINE_REPLAY, DISABLED }

/** 输入为已等比缩放并 letterbox 到 320×320 的裁剪（填充值 114），输出已做 NMS。 */
interface Detector : AutoCloseable {
    val backend: DetectorBackend
    val modelSha256: String
    fun detect(input: RgbImage): List<Detection>
}

/** 一个出牌区在某一采样时刻的观察结果。 */
data class ZoneObservation(
    val zone: PlayZone,
    /** 白色牌面面积达到可见阈值（hasVisibleCardArea）。 */
    val cardVisible: Boolean,
    /** 与上一次采样相比画面静止（无动画）。 */
    val still: Boolean,
    /** 只在需要识别时填；不需要识别的采样为 null。 */
    val detections: List<Detection>?
)

/** 一次采样（手机约 50~100ms 一次）的全部观察。 */
data class FrameObservation(
    val timestampMs: Long,
    val tableVisible: Boolean,
    val zones: Map<PlayZone, ZoneObservation>
)

/** 状态机给出的一次决策：要提交给 SessionActor 的命令，附带便于对照真值的出处。 */
data class PipelineDecision(
    val command: LedgerCommand,
    val zone: PlayZone?,
    val timestampMs: Long
)

/** 六区出牌状态机。只读账本状态，不直接写账本；写入由调用方交给 SessionActor。 */
interface PlayPipeline {
    fun onObservation(observation: FrameObservation, ledger: GameState): List<PipelineDecision>
    fun reset()
}

sealed interface PlayRuleCheck {
    data object Valid : PlayRuleCheck
    data class Suspicious(val code: String, val detail: String) : PlayRuleCheck
}

/** 够级出牌规则：识别出的一手牌若不可能是合法出牌，不入账而是重试。 */
fun interface PlayRuleValidator {
    fun check(cards: RankCounts): PlayRuleCheck
}
