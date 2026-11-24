package com.example.cardcounter.eval

import com.example.cardcounter.capture.PlaySource
import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.domain.DispatchResult
import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.RankCompleteness
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.recognition.FrameObservation
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
        val handCount = game.selfHandCount.takeIf { it > 0 } ?: game.selfHand.total()
        require(handCount > 0) { "游戏 ${game.gameId} 的 selfHandCount 无效，无法锁定手牌" }
        val completeness =
            if (game.selfHand.total() == handCount) RankCompleteness.COMPLETE else RankCompleteness.PARTIAL

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
            for (frame in frames.sortedBy { it.timestampMs }) {
                for (decision in pipeline.onObservation(frame, ledger.state)) {
                    val result = actor.post(decision.command)
                    if (result is DispatchResult.Paused) pauses++
                    if (result is DispatchResult.Rejected) rejections++
                    decisions += RecordedDecision(decision.timestampMs, decision.zone, decision.command, result)
                }
            }
        } finally {
            actor.shutdown()
        }
        val accepted = decisions.mapNotNull { decision ->
            if (!decision.isAccepted) return@mapNotNull null
            when (val command = decision.command) {
                is LedgerCommand.AcceptOutsidePlay -> AcceptedPlay(
                    decision.timestampMs,
                    PlayZone.entries.first {
                        it.source == PlaySource.OUTSIDE && it.seat == command.seat
                    },
                    command.cards,
                    command.cardCount
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

    private fun DispatchResult.isAcceptedLedgerResult(): Boolean =
        this is DispatchResult.Accepted || this is DispatchResult.AcceptedQuantityOnly
}
