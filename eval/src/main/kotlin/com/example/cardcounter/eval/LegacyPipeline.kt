package com.example.cardcounter.eval

import com.example.cardcounter.capture.PlaySource
import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.capture.RoiPlayEventCache
import com.example.cardcounter.capture.StablePlayGate
import com.example.cardcounter.capture.TurnTracker
import com.example.cardcounter.capture.canFastConfirmPlay
import com.example.cardcounter.capture.isPlayVisible
import com.example.cardcounter.capture.isPublicRankBand
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.domain.SessionPhase
import com.example.cardcounter.recognition.Detection
import com.example.cardcounter.recognition.FrameObservation
import com.example.cardcounter.recognition.PipelineDecision
import com.example.cardcounter.recognition.PlayPipeline
import com.example.cardcounter.storage.LedgerCommand
import java.util.UUID

/** 一次最终化尝试的结果，用于诊断，不写入账本。 */
enum class LegacyOutcome { ACCEPTED, NO_CONSENSUS, NOT_ACTIVE, DUPLICATE_SUPPRESSED }

data class LegacyAudit(
    val zone: PlayZone,
    val timestampMs: Long,
    val outcome: LegacyOutcome,
    val cards: RankCounts?,
    val frameCount: Int
)

/**
 * 手机现行出牌判定（W5 新状态机到位前的临时裁判实现）：
 * RoiPlayEventCache 停稳交出 → 多帧检测投票 → StablePlayGate.accept；
 * 出牌圆标（TurnBadge）经 TurnTracker 告诉去重门“轮到过这一家”，与手机同一套状态机。
 * 帧按时间取最后 N 帧投票，与 CaptureProbeService.finalizeRoiEvent 一致。
 * 决策时间取这一手最后一帧证据的时间，不是交出时刻（清桌交出要多等 clearSamples 次采样）。
 * 回放走逐区路径：每个区判定前读最新账本、判定后立即提交；同帧前一区让账本暂停后，
 * 后一区走“非 ACTIVE”分支，不动去重门控，与手机 finalizeRoiEvent 逐区串行一致。
 */
class LegacyPipeline(
    stableSamples: Int = 15,
    clearSamples: Int = 6,
    maxFrames: Int = 8,
    private val finalizeFrameCount: Int = 3,
    private val onAudit: (LegacyAudit) -> Unit = {}
) : StepwisePlayPipeline {
    private data class ZoneSample(val timestampMs: Long, val still: Boolean, val detections: List<Detection>?)
    private data class Candidate(val cards: RankCounts, val minimumConfidence: Float)

    private val cache = RoiPlayEventCache<ZoneSample>(
        maxFrames = maxFrames,
        clearSamples = clearSamples,
        stableSamples = stableSamples,
        isStill = { _, current -> current.still }
    )
    private val gate = StablePlayGate()
    private val turns = TurnTracker()
    private var generation = 0L
    private val auditLog = mutableListOf<LegacyAudit>()

    /** 本次回放的全部最终化尝试，按发生顺序。 */
    val audits: List<LegacyAudit> get() = auditLog.toList()

    init {
        cache.reset(generation, primeExistingTable = false)
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
        for (zone in PlayZone.entries) {
            // 自己那一区的出牌完全不管：不判定、不发命令。
            if (zone.source == PlaySource.SELF) continue
            // 缺省的区表示这一帧没有该区观察（DATA-FORMATS 第 5 节）：不推进清桌计数，也不动门控。
            val observed = observation.zones[zone] ?: continue
            val visible = tableVisible && isPlayVisible(observed.cardVisible)
            // 桌面可见时才看“出牌”圆标；这一区牌已收走（且没有待兜底交出的牌）时轮到这一家，
            // 之后再出一样的牌才算新的一手。待兜底交出的牌先交出入账，再算轮到他。
            val zoneEmpty = !visible && !cache.hasUndelivered(zone, generation)
            if (tableVisible && turns.update(zone, observed.turnBadge, zoneEmpty = zoneEmpty)) gate.noteTurn(zone)
            val sample = if (visible) ZoneSample(observation.timestampMs, observed.still, observed.detections) else null
            val finalized = cache.observe(zone, tableVisible, visible, sample, generation) ?: continue
            // 用这一手最后一帧还在桌上的时间：清桌交出要再等 clearSamples 次采样，
            // 若用交出时刻，延迟随采样间隔变化，会掉出真值对齐时间窗。
            val evidenceMs = finalized.frames.lastOrNull()?.timestampMs ?: observation.timestampMs
            finalize(finalized, evidenceMs, ledger())?.let(commit)
        }
    }

    override fun reset() {
        generation++
        cache.reset(generation, primeExistingTable = false)
        gate.reset()
        turns.reset()
        auditLog.clear()
    }

    private fun finalize(
        finalized: RoiPlayEventCache.Finalized<ZoneSample>,
        timestampMs: Long,
        ledger: GameState
    ): PipelineDecision? {
        val candidates = finalized.frames
            .takeLast(finalizeFrameCount)
            .mapNotNull { sample -> sample.detections?.let(::candidateOf) }
        val winner = candidates
            .filter { it.cards.total() > 0 }
            .groupBy { it.cards }
            .maxWithOrNull(
                compareBy<Map.Entry<RankCounts, List<Candidate>>> { it.value.size }
                    .thenBy { entry -> entry.value.minOf { it.minimumConfidence } }
            )
        val cards = winner?.let { entry ->
            entry.key.takeIf {
                entry.value.size >= 2 || canFastConfirmPlay(it, entry.value.first().minimumConfidence)
            }
        }
        if (cards == null) {
            audit(LegacyAudit(finalized.zone, timestampMs, LegacyOutcome.NO_CONSENSUS, null, finalized.frames.size))
            return null
        }
        if (ledger.phase != SessionPhase.ACTIVE) {
            audit(LegacyAudit(finalized.zone, timestampMs, LegacyOutcome.NOT_ACTIVE, cards, finalized.frames.size))
            return null
        }
        val stable = gate.accept(finalized.zone, cards) ?: run {
            audit(
                LegacyAudit(finalized.zone, timestampMs, LegacyOutcome.DUPLICATE_SUPPRESSED, cards, finalized.frames.size)
            )
            return null
        }
        audit(LegacyAudit(finalized.zone, timestampMs, LegacyOutcome.ACCEPTED, stable.cards, finalized.frames.size))
        val evidenceId = UUID.randomUUID().toString()
        val command = LedgerCommand.AcceptOutsidePlay(
            stable.cards, requireNotNull(finalized.zone.seat), evidenceId
        )
        return PipelineDecision(command, finalized.zone, timestampMs)
    }

    /** 与 detectYoloCards(publicPlay = true) 等价：只保留点数带内的框，按点数计数。 */
    private fun candidateOf(detections: List<Detection>): Candidate {
        val counts = mutableMapOf<Rank, Int>()
        var minimum = 0f
        var seen = 0
        for (detection in detections) {
            if (!isPublicRankBand(detection.cy)) continue
            counts[detection.rank] = (counts[detection.rank] ?: 0) + 1
            minimum = if (seen == 0) detection.confidence else minOf(minimum, detection.confidence)
            seen++
        }
        return Candidate(RankCounts.of(counts), minimum)
    }
}
