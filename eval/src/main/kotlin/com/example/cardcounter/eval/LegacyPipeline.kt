package com.example.cardcounter.eval

import com.example.cardcounter.capture.PlaySource
import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.capture.RoiPlayEventCache
import com.example.cardcounter.capture.StablePlayGate
import com.example.cardcounter.capture.canFastConfirmPlay
import com.example.cardcounter.capture.isPlayVisible
import com.example.cardcounter.capture.isPublicRankBand
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.domain.RankCompleteness
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
 * RoiPlayEventCache 停稳/清桌交出 → 多帧检测投票 → StablePlayGate.acceptStable。
 *
 * 与 CaptureProbeService.finalizeRoiEvent 的已知差异：没有手牌模板融合
 * （fuseSinglePublicPlay 需要位图），帧按时间取最近 N 帧而不是按 quality 排序。
 * 决策时间取这一手最后一帧证据的时间，不是交出时刻（清桌交出要多等 clearSamples 次采样）。
 */
class LegacyPipeline(
    stableSamples: Int = 15,
    clearSamples: Int = 6,
    maxFrames: Int = 8,
    private val finalizeFrameCount: Int = 3,
    private val onAudit: (LegacyAudit) -> Unit = {}
) : PlayPipeline {
    private data class ZoneSample(val timestampMs: Long, val still: Boolean, val detections: List<Detection>?)
    private data class Candidate(val cards: RankCounts, val minimumConfidence: Float)

    private val cache = RoiPlayEventCache<ZoneSample>(
        maxFrames = maxFrames,
        clearSamples = clearSamples,
        stableSamples = stableSamples,
        isStill = { _, current -> current.still }
    )
    private val gate = StablePlayGate()
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

    override fun onObservation(observation: FrameObservation, ledger: GameState): List<PipelineDecision> {
        val decisions = mutableListOf<PipelineDecision>()
        val tableVisible = observation.tableVisible
        for (zone in PlayZone.entries) {
            val observed = observation.zones[zone]
            val visible = tableVisible && isPlayVisible(observed?.cardVisible == true)
            // 手机在“看得见桌面但该区没牌”时用零候选清门控；桌面不可见时不动门控。
            if (tableVisible && !visible) {
                gate.update(zone, RankCounts.zero())
            }
            val sample = if (visible) ZoneSample(observation.timestampMs, observed!!.still, observed.detections) else null
            val finalized = cache.observe(zone, tableVisible, visible, sample, generation) ?: continue
            // 用这一手最后一帧还在桌上的时间：清桌交出要再等 clearSamples 次采样，
            // 若用交出时刻，延迟随采样间隔变化，会掉出真值对齐时间窗。
            val evidenceMs = finalized.frames.lastOrNull()?.timestampMs ?: observation.timestampMs
            decisions += finalize(finalized, evidenceMs, ledger)
        }
        return decisions
    }

    override fun reset() {
        generation++
        cache.reset(generation, primeExistingTable = false)
        gate.reset()
        auditLog.clear()
    }

    private fun finalize(
        finalized: RoiPlayEventCache.Finalized<ZoneSample>,
        timestampMs: Long,
        ledger: GameState
    ): List<PipelineDecision> {
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
            return emptyList()
        }
        if (ledger.phase != SessionPhase.ACTIVE) {
            audit(LegacyAudit(finalized.zone, timestampMs, LegacyOutcome.NOT_ACTIVE, cards, finalized.frames.size))
            return emptyList()
        }
        val stable = gate.acceptStable(finalized.zone, cards) ?: run {
            audit(
                LegacyAudit(finalized.zone, timestampMs, LegacyOutcome.DUPLICATE_SUPPRESSED, cards, finalized.frames.size)
            )
            return emptyList()
        }
        audit(LegacyAudit(finalized.zone, timestampMs, LegacyOutcome.ACCEPTED, stable.cards, finalized.frames.size))
        val evidenceId = UUID.randomUUID().toString()
        val command = when (finalized.zone.source) {
            PlaySource.SELF -> LedgerCommand.AcceptSelfPlay(
                stable.cards, stable.cards.total(), RankCompleteness.COMPLETE, evidenceId
            )
            PlaySource.OUTSIDE -> LedgerCommand.AcceptOutsidePlay(
                stable.cards, stable.cards.total(), requireNotNull(finalized.zone.seat),
                RankCompleteness.COMPLETE, evidenceId
            )
        }
        return listOf(PipelineDecision(command, finalized.zone, timestampMs))
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
