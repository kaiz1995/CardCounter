package com.example.cardcounter.eval

import com.example.cardcounter.capture.PlaySource
import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.domain.DispatchResult
import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.GameState
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

    fun onDecisionResult(decision: PipelineDecision, result: DispatchResult) {}
}

/** 一条已提交给账本的决策及其结果。 */
data class RecordedDecision(
    val timestampMs: Long,
    val zone: PlayZone?,
    val command: LedgerCommand,
    val result: DispatchResult
) {
    /** 真正进入账本。 */
    val isAccepted: Boolean
        get() = result is DispatchResult.Accepted
}

/** 进入账本的一手外面出牌，供匹配器使用；张数就是点数向量合计。 */
data class AcceptedPlay(
    val timestampMs: Long,
    val zone: PlayZone,
    val cards: RankCounts,
    val cardCount: Int = cards.total()
)

data class ReplayResult(
    val decisions: List<RecordedDecision>,
    val accepted: List<AcceptedPlay>,
    val audits: List<LegacyAudit>,
    val finalState: GameState,
    val pauseCount: Int,
    val rejectionCount: Int
) {
    /** 本局是否确认了自己的手牌；没确认时横条不扣自己的牌，剩余牌不可算。 */
    val handConfirmed: Boolean get() = finalState.handConfirmed
    val retryCount: Int get() = audits.count {
        it.outcome == LegacyOutcome.NO_CONSENSUS || it.outcome == LegacyOutcome.RULE_SUSPICIOUS
    }
    val suppressedDuplicateCount: Int get() = audits.count { it.outcome == LegacyOutcome.DUPLICATE_SUPPRESSED }
    val notActiveCount: Int get() = audits.count { it.outcome == LegacyOutcome.NOT_ACTIVE }
}

object Replay {
    /**
     * 开新局 → 手牌完整就确认 → 按时间顺序喂帧 → 把每个决策交给同一个 SessionActor。
     * 与手机一样，状态机只读账本，写入全部经单写者串行执行。
     * games.json 的 selfHand 点数合计等于 selfHandCount 才发 ConfirmHand；
     * 为空或不完整就不确认（横条按未确认算），报告统一标 self_hand_unknown。
     */
    fun run(
        game: GameMeta,
        frames: List<FrameObservation>,
        pipeline: PlayPipeline,
        /** 回放结束后才取：审计记录是回放过程中产生的。 */
        audits: () -> List<LegacyAudit> = { emptyList() }
    ): ReplayResult {
        val known = game.selfHandCount
        require(known == null || known > 0) { "游戏 ${game.gameId} 的 selfHandCount 必须为正，无法确认手牌" }

        val ledger = InMemoryLedger()
        val actor = SessionActor(ledger)
        val decisions = mutableListOf<RecordedDecision>()
        var pauses = 0
        var rejections = 0
        try {
            actor.post(LedgerCommand.NewSession("eval-session-${game.gameId}"))
            if (game.selfHandComplete) {
                val confirmResult = actor.post(
                    LedgerCommand.ConfirmHand(game.selfHand, game.selfHand.total(), "eval-hand-${game.gameId}")
                )
                check(confirmResult is DispatchResult.Accepted) {
                    "确认手牌失败: ${(confirmResult as? DispatchResult.Rejected)?.reason ?: confirmResult}"
                }
            }
            fun commit(decision: PipelineDecision) {
                val result = actor.post(decision.command)
                if (result is DispatchResult.Paused) pauses++
                if (result is DispatchResult.Rejected) rejections++
                decisions += RecordedDecision(decision.timestampMs, decision.zone, decision.command, result)
                if (pipeline is StepwisePlayPipeline) {
                    pipeline.onDecisionResult(decision, result)
                }
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
                    decision.timestampMs, outsideZoneOf(decision.zone, command), command.cards
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
            rejectionCount = rejections
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

    /** 优先用决策自带的出牌区；没有时按座位找（座位必填，一定找得到外面区）。 */
    internal fun outsideZoneOf(zone: PlayZone?, command: LedgerCommand.AcceptOutsidePlay): PlayZone {
        if (zone != null) {
            require(zone.source == PlaySource.OUTSIDE) { "外面出牌决策的出牌区不是外面区: $zone" }
            return zone
        }
        return PlayZone.entries.first { it.source == PlaySource.OUTSIDE && it.seat == command.seat }
    }

    private fun LedgerCommand.isPlay(): Boolean = this is LedgerCommand.AcceptOutsidePlay
}
