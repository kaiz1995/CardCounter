package com.example.cardcounter.eval

import com.example.cardcounter.capture.PlaySource
import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.domain.DispatchResult
import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.RankCompleteness
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.domain.SessionPhase
import com.example.cardcounter.recognition.FrameObservation
import com.example.cardcounter.recognition.PipelineDecision
import com.example.cardcounter.recognition.PlayPipeline
import com.example.cardcounter.storage.LedgerCommand
import com.example.cardcounter.storage.LedgerGateway
import com.example.cardcounter.storage.SessionActor

/**
 * 内存账本：与手机 SessionRepository 相同的接口，内部直接调用 GameReducer。
 * 不写盘、不依赖 android。
 */
class InMemoryLedger : LedgerGateway {
    private var current: GameState = GameReducer.newSession(GameEvent.NewSession("eval-memory-init"))

    override val state: GameState get() = current

    override fun dispatchDetailed(event: GameEvent): DispatchResult =
        GameReducer.apply(current, event).also { current = it.state }
}

/**
 * 逐区提交的管线：每个区判定前读最新账本、判定后立即提交，与手机 finalizeRoiEvent 逐区串行一致。
 * PlayPipeline 只拿帧开始时的账本快照，同帧前一区让账本暂停后，后一区仍会按 ACTIVE 判定并动去重门控；
 * 接口重做（W5）前在评测侧补这条路径，不改 core 的公共签名。
 */
interface StepwisePlayPipeline : PlayPipeline {
    fun onObservationStepwise(
        observation: FrameObservation,
        ledger: () -> GameState,
        commit: (PipelineDecision) -> Unit
    )
}

/** 一条已提交给账本的决策及其结果。 */
data class RecordedDecision(
    val timestampMs: Long,
    val zone: PlayZone?,
    val command: LedgerCommand,
    val result: DispatchResult
) {
    /** 真正进入账本（含只进数量账）。 */
    val isAccepted: Boolean
        get() = result is DispatchResult.Accepted || result is DispatchResult.AcceptedQuantityOnly
}

/** 进入账本的一手牌，供匹配器使用。 */
data class AcceptedPlay(
    val timestampMs: Long,
    val zone: PlayZone,
    val cards: RankCounts,
    val cardCount: Int
)

data class ReplayResult(
    val decisions: List<RecordedDecision>,
    val accepted: List<AcceptedPlay>,
    val audits: List<LegacyAudit>,
    val finalState: GameState,
    val pauseCount: Int,
    val rejectionCount: Int,
    val lockHandCompleteness: RankCompleteness
) {
    val retryCount: Int get() = audits.count { it.outcome == LegacyOutcome.NO_CONSENSUS }
    val suppressedDuplicateCount: Int get() = audits.count { it.outcome == LegacyOutcome.DUPLICATE_SUPPRESSED }
    val notActiveCount: Int get() = audits.count { it.outcome == LegacyOutcome.NOT_ACTIVE }
}

object Replay {
    /**
     * 开新局 → 锁手牌 → 按时间顺序喂帧 → 把每个决策交给同一个 SessionActor。
     * 与手机一样，状态机只读账本，写入全部经单写者串行执行。
     */
    fun run(
        game: GameMeta,
        frames: List<FrameObservation>,
        pipeline: PlayPipeline,
        /** 回放结束后才取：审计记录是回放过程中产生的。 */
        audits: () -> List<LegacyAudit> = { emptyList() }
    ): ReplayResult {
        // 不拿 selfHand 合计顶替：手牌点数残缺时会被误当成完整，伪造出终局剩余牌。
        require(game.selfHandCount > 0) { "游戏 ${game.gameId} 的 selfHandCount 必须为正，无法锁定手牌" }
        val handCount = game.selfHandCount
        val completeness = if (game.selfHandComplete) RankCompleteness.COMPLETE else RankCompleteness.PARTIAL

        val ledger = InMemoryLedger()
        val actor = SessionActor(ledger)
        val decisions = mutableListOf<RecordedDecision>()
        var pauses = 0
        var rejections = 0
        try {
            actor.post(LedgerCommand.NewSession("eval-session-${game.gameId}"))
            val lockResult = actor.post(
                LedgerCommand.LockHand(
                    game.selfHand, handCount, completeness, "eval-lock-${game.gameId}"
                )
            )
            check(lockResult.isAcceptedLedgerResult()) {
                "锁定手牌失败: ${(lockResult as? DispatchResult.Rejected)?.reason ?: lockResult}"
            }
            fun commit(decision: PipelineDecision) {
                val result = actor.post(decision.command)
                if (result is DispatchResult.Paused) pauses++
                if (result is DispatchResult.Rejected) rejections++
                decisions += RecordedDecision(decision.timestampMs, decision.zone, decision.command, result)
            }
            for (frame in framesInPlay(game, frames)) {
                if (pipeline is StepwisePlayPipeline) {
                    // 逐区读最新账本、逐区提交：同帧前一区暂停后，后一区按手机逻辑走“非 ACTIVE”分支，
                    // 不动去重门控，审计记 NOT_ACTIVE。
                    pipeline.onObservationStepwise(frame, { ledger.state }, ::commit)
                } else {
                    for (decision in pipeline.onObservation(frame, ledger.state)) {
                        // 只拿帧开始快照的管线：前一个区已让账本暂停，后面的出牌决策直接跳过，
                        // 不进账本也不记拒绝。
                        if (ledger.state.phase != SessionPhase.ACTIVE && decision.command.isPlay()) continue
                        commit(decision)
                    }
                }
            }
        } finally {
            actor.shutdown()
        }
        val accepted = decisions.mapNotNull { decision ->
            if (!decision.isAccepted) return@mapNotNull null
            when (val command = decision.command) {
                is LedgerCommand.AcceptOutsidePlay -> AcceptedPlay(
                    decision.timestampMs, outsideZoneOf(decision.zone, command), command.cards, command.cardCount
                )
                is LedgerCommand.AcceptSelfPlay -> AcceptedPlay(
                    decision.timestampMs, PlayZone.SELF, command.cards, command.cardCount
                )
                else -> null
            }
        }
        return ReplayResult(
            decisions = decisions,
            accepted = accepted,
            audits = audits(),
            finalState = ledger.state,
            pauseCount = pauses,
            rejectionCount = rejections,
            lockHandCompleteness = completeness
        )
    }

    /**
     * 只回放正式出牌阶段 [prePlayEndMs, endMs]（字段为 0 表示不限）：手机在首出前的发牌、交贡、
     * 买 3 买 4 阶段不判出牌，真值也只记首出之后，观察文件可以覆盖整局。
     */
    internal fun framesInPlay(game: GameMeta, frames: List<FrameObservation>): List<FrameObservation> =
        frames
            .filter { frame ->
                (game.prePlayEndMs <= 0 || frame.timestampMs >= game.prePlayEndMs) &&
                    (game.endMs <= 0 || frame.timestampMs <= game.endMs)
            }
            .sortedBy { it.timestampMs }

    /** 优先用决策自带的出牌区；没有时按座位找，两者都没有就无法对齐真值。 */
    internal fun outsideZoneOf(zone: PlayZone?, command: LedgerCommand.AcceptOutsidePlay): PlayZone {
        if (zone != null) {
            require(zone.source == PlaySource.OUTSIDE) { "外面出牌决策的出牌区不是外面区: $zone" }
            return zone
        }
        val seat = requireNotNull(command.seat) { "外面出牌决策既没有出牌区也没有座位，无法对齐真值" }
        return PlayZone.entries.first { it.source == PlaySource.OUTSIDE && it.seat == seat }
    }

    private fun LedgerCommand.isPlay(): Boolean =
        this is LedgerCommand.AcceptOutsidePlay || this is LedgerCommand.AcceptSelfPlay

    private fun DispatchResult.isAcceptedLedgerResult(): Boolean =
        this is DispatchResult.Accepted || this is DispatchResult.AcceptedQuantityOnly
}
