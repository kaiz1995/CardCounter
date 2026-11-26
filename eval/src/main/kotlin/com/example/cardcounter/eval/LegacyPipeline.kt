package com.example.cardcounter.eval

import com.example.cardcounter.capture.FinalizeReason
import com.example.cardcounter.capture.OpeningPlayGate
import com.example.cardcounter.capture.PlayFrameVote
import com.example.cardcounter.capture.PlayGateDecision
import com.example.cardcounter.capture.PlaySource
import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.capture.RoiPlayEventCache
import com.example.cardcounter.capture.StablePlayGate
import com.example.cardcounter.capture.ZoneSignalTracker
import com.example.cardcounter.capture.routeZoneSignals
import com.example.cardcounter.capture.isPlayVisible
import com.example.cardcounter.capture.isPublicRankBand
import com.example.cardcounter.domain.DispatchResult
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.domain.SessionPhase
import com.example.cardcounter.recognition.Detection
import com.example.cardcounter.recognition.FrameObservation
import com.example.cardcounter.recognition.GoujiPlayRules
import com.example.cardcounter.recognition.PipelineDecision
import com.example.cardcounter.recognition.PlayRuleCheck
import com.example.cardcounter.storage.LedgerCommand
import com.example.cardcounter.vision.RankModelContract
import com.example.cardcounter.vision.YoloDetection
import java.util.UUID

/** 一次最终化尝试的结果，用于诊断，不写入账本。 */
enum class LegacyOutcome {
    ACCEPTED,
    NO_CONSENSUS,
    RULE_SUSPICIOUS,
    PRE_PLAY_SKIPPED,
    NOT_ACTIVE,
    DUPLICATE_SUPPRESSED
}

data class LegacyAudit(
    val zone: PlayZone,
    val timestampMs: Long,
    val outcome: LegacyOutcome,
    val cards: RankCounts?,
    val frameCount: Int,
    /** 这一手是怎么交出来的：停稳 / 被盖 / 清桌 / 牌桌被挡（补记重试时为 null）。 */
    val reason: FinalizeReason? = null,
    /** 诊断详情（如规则校验或去重拦截原因）。 */
    val detail: String? = null
)

/**
 * 手机现行出牌判定（与 CaptureProbeService 同一套状态机与投票合并）：
 * RoiPlayEventCache 停稳交出 → 多帧检测按牌位合并投票 → GoujiPlayRules 校验 →
 * OpeningPlayGate 开局换牌期拦截 → StablePlayGate 只读检查 → 账本 Accepted 后提交门控状态；
 * 出牌圆标（TurnBadge）经 TurnTracker 告诉去重门“轮到过这一家”，同时追踪 1.5s 长清桌。
 * 帧按时间取最后 N 帧投票，与 CaptureProbeService.finalizeRoiEvent 一致。
 * 决策时间取这一手最后一帧证据的时间，不是交出时刻（清桌交出要多等 clearSamples 次采样；
 * 被盖、牌桌被挡交出的也是上一手，同样取它在桌上的最后一帧）。
 * 回放走逐区路径：每个区判定前读最新账本、判定后立即提交；同帧前一区让账本暂停后，
 * 后一区走“非 ACTIVE”分支，不动去重门控，与手机 finalizeRoiEvent 逐区串行一致。
 */
class LegacyPipeline(
    stableSamples: Int = 15,
    clearSamples: Int = 6,
    maxFrames: Int = 8,
    /** 回放约 97ms 一次采样，取 2 次（约 194ms）；手机 50ms 一次，取 3 次（约 150ms）。 */
    coveredStillSamples: Int = 2,
    private val finalizeFrameCount: Int = 3,
    private val requireOpeningSignal: Boolean = true,
    private val onAudit: (LegacyAudit) -> Unit = {}
) : StepwisePlayPipeline {
    private data class ZoneSample(val timestampMs: Long, val still: Boolean, val detections: List<Detection>?)

    private val cache = RoiPlayEventCache<ZoneSample>(
        maxFrames = maxFrames,
        clearSamples = clearSamples,
        stableSamples = stableSamples,
        coveredStillSamples = coveredStillSamples,
        isStill = { _, current -> current.still }
    )
    private val gate = StablePlayGate()
    private val signals = ZoneSignalTracker()
    private val openingGate = OpeningPlayGate()
    private var generation = 0L

    /** 当前这一帧的采样时间：账本接受后提交去重门要用和检查时同一个时间。 */
    private var currentNowMs = 0L
    private val auditLog = mutableListOf<LegacyAudit>()

    /** 本次回放的全部最终化尝试，按发生顺序。 */
    val audits: List<LegacyAudit> get() = auditLog.toList()

    init {
        cache.reset(generation, primeExistingTable = false)
        openingGate.reset(alreadyInPlay = !requireOpeningSignal)
    }

    private fun audit(entry: LegacyAudit) {
        auditLog += entry
        onAudit(entry)
    }

    /** 只拿帧开始时账本快照的调用方式；Replay 用的是下面的逐区版本。 */
    override fun onObservation(observation: FrameObservation, ledger: GameState): List<PipelineDecision> {
        val decisions = mutableListOf<PipelineDecision>()
        onObservationStepwise(observation, { ledger }) { decisions += it }
        return decisions
    }

    override fun onObservationStepwise(
        observation: FrameObservation,
        ledger: () -> GameState,
        commit: (PipelineDecision) -> Unit
    ) {
        val tableVisible = observation.tableVisible
        currentNowMs = observation.timestampMs
        for (zone in PlayZone.entries) {
            // 自己那一区的出牌完全不管：不判定、不发命令。
            if (zone.source == PlaySource.SELF) continue
            // 缺省的区表示这一帧没有该区观察（DATA-FORMATS 第 5 节）：不推进清桌计数，也不动门控。
            // 牌桌不可见时手机照样逐区过一遍（只用来交出没交出的一手），回放同样处理，缺省的区也不例外。
            val observed = observation.zones[zone]
            if (observed == null && tableVisible) continue
            val visible = tableVisible && observed != null && isPlayVisible(observed.cardVisible)
            val sample = if (visible) {
                ZoneSample(observation.timestampMs, observed.still, observed.detections)
            } else {
                null
            }
            val finalized = cache.observe(zone, tableVisible, visible, sample, generation)
            if (finalized != null) {
                // 用这一手最后一帧还在桌上的时间：清桌交出要再等 clearSamples 次采样，被盖交出时新牌已经上桌，
                // 牌桌被挡交出时这一手已经看不到；若用交出时刻，延迟随采样间隔变化，会掉出真值对齐时间窗。
                val evidenceMs = finalized.frames.lastOrNull()?.timestampMs ?: observation.timestampMs
                finalize(finalized, evidenceMs, observation.timestampMs, ledger())?.let(commit)
            }
            // “出牌”圆标和清桌计时放在交出之后看（与手机一致）：清桌这次采样兜底交出的一手先入账，这次采样的信号再生效。
            // 牌桌被遮挡时也要采样，空桌计时和圆标连续计数在里面归零；手机和评测共用 [ZoneSignalTracker] 与 [routeZoneSignals]。
            val signal = signals.sample(
                zone,
                badgeShown = tableVisible && observed?.turnBadge == true,
                cleared = cache.isCleared(zone, generation),
                zoneEmpty = !visible && !cache.hasUndelivered(zone, generation),
                tableVisible = tableVisible,
                nowMs = observation.timestampMs
            )
            if (signal.any) {
                val retries = routeZoneSignals(
                    zone, signal, observation.timestampMs, gate, openingGate, ledger().handConfirmed
                ) { other -> isOnTable(observation, other) }
                for (retry in retries) replaySkipped(retry, ledger())?.let(commit)
            }
        }
    }

    override fun onDecisionResult(decision: PipelineDecision, result: DispatchResult) {
        val zone = decision.zone ?: return
        val command = decision.command as? LedgerCommand.AcceptOutsidePlay ?: return
        if (result is DispatchResult.Accepted) {
            gate.commitAccepted(zone, command.cards, currentNowMs) { cache.isCleared(it, generation) }
        }
    }

    override fun reset() {
        generation++
        cache.reset(generation, primeExistingTable = false)
        gate.reset()
        signals.reset()
        openingGate.reset(alreadyInPlay = !requireOpeningSignal)
        auditLog.clear()
    }

    private fun isOnTable(observation: FrameObservation, zone: PlayZone): Boolean =
        observation.tableVisible && (observation.zones[zone]?.let { isPlayVisible(it.cardVisible) } ?: false)

    private fun finalize(
        finalized: RoiPlayEventCache.Finalized<ZoneSample>,
        timestampMs: Long,
        nowMs: Long,
        ledger: GameState
    ): PipelineDecision? {
        val frameDetections = finalized.frames
            .takeLast(finalizeFrameCount)
            .mapNotNull { sample -> sample.detections?.let(::toYoloDetections) }
        val voteOutcome = PlayFrameVote.resolve(frameDetections)
        val cards = voteOutcome?.cards
        if (cards == null) {
            audit(
                LegacyAudit(
                    finalized.zone, timestampMs, LegacyOutcome.NO_CONSENSUS, null, finalized.frames.size,
                    finalized.reason
                )
            )
            return null
        }
        val ruleCheck = GoujiPlayRules.check(cards)
        if (ruleCheck is PlayRuleCheck.Suspicious) {
            audit(
                LegacyAudit(
                    finalized.zone, timestampMs, LegacyOutcome.RULE_SUSPICIOUS,
                    cards, finalized.frames.size, finalized.reason,
                    detail = "${ruleCheck.code}:${ruleCheck.detail}"
                )
            )
            return null
        }
        if (ledger.phase != SessionPhase.ACTIVE) {
            audit(
                LegacyAudit(
                    finalized.zone, timestampMs, LegacyOutcome.NOT_ACTIVE, cards, finalized.frames.size,
                    finalized.reason
                )
            )
            return null
        }
        return admit(finalized.zone, cards, finalized.frames.size, finalized.reason, timestampMs, nowMs, ledger)
    }

    /** 开局门因圆标打开后补记门控打开前交出过、牌还在桌上的一手：规则早已校验过，只重走账本状态、开局门和去重门（与手机一致）。 */
    private fun replaySkipped(play: OpeningPlayGate.SkippedPlay, ledger: GameState): PipelineDecision? {
        if (ledger.phase != SessionPhase.ACTIVE) {
            audit(LegacyAudit(play.zone, play.atMs, LegacyOutcome.NOT_ACTIVE, play.cards, 0))
            return null
        }
        return admit(play.zone, play.cards, 0, null, play.atMs, currentNowMs, ledger)
    }

    /** 开局门 → 去重门检查 → 生成账本命令；正常交出和补记共用。 */
    private fun admit(
        zone: PlayZone,
        cards: RankCounts,
        frameCount: Int,
        finalizeReason: FinalizeReason?,
        timestampMs: Long,
        nowMs: Long,
        ledger: GameState
    ): PipelineDecision? {
        if (requireOpeningSignal && !openingGate.isReady(ledger.handConfirmed)) {
            // 记下这一手：开局门稍后因圆标打开时，桌上还摆着的会补记。
            openingGate.notePrePlaySkipped(zone, cards, nowMs)
            audit(
                LegacyAudit(
                    zone, timestampMs, LegacyOutcome.PRE_PLAY_SKIPPED,
                    cards, frameCount, finalizeReason, detail = "PRE_PLAY_SKIPPED"
                )
            )
            return null
        }
        val stable = when (val decision = gate.check(zone, cards, nowMs)) {
            is PlayGateDecision.Accept -> decision.play
            is PlayGateDecision.Suppressed -> {
                audit(
                    LegacyAudit(
                        zone, timestampMs, LegacyOutcome.DUPLICATE_SUPPRESSED,
                        cards, frameCount, finalizeReason, detail = decision.reason.name
                    )
                )
                return null
            }
        }
        audit(LegacyAudit(zone, timestampMs, LegacyOutcome.ACCEPTED, stable.cards, frameCount, finalizeReason))
        val evidenceId = UUID.randomUUID().toString()
        val command = LedgerCommand.AcceptOutsidePlay(stable.cards, requireNotNull(zone.seat), evidenceId)
        return PipelineDecision(command, zone, timestampMs)
    }

    /** 只保留点数带内的框（[isPublicRankBand]，为兼容旧模型回放保留），换算到 320 画布像素。 */
    private fun toYoloDetections(detections: List<Detection>): List<YoloDetection> {
        val scale = RankModelContract.INPUT_SIZE.toFloat()
        return detections
            .filter { isPublicRankBand(it.cy) }
            .map { d ->
                YoloDetection(
                    rank = d.rank,
                    rawLabel = d.rank.name,
                    confidence = d.confidence,
                    centerX = d.cx * scale,
                    centerY = d.cy * scale,
                    width = d.w * scale,
                    height = d.h * scale
                )
            }
    }
}
