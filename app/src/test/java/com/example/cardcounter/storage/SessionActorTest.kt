package com.example.cardcounter.storage

import com.example.cardcounter.domain.DispatchResult
import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.OutsideSeat
import com.example.cardcounter.domain.PauseReason
import com.example.cardcounter.domain.RankCompleteness
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.domain.SessionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionActorTest {
    /** 内存账本：与 SessionRepository 相同的派发语义（被拒事件不落账）。 */
    private class FakeLedger : LedgerGateway {
        private var current: GameState = GameReducer.newSession()

        override val state: GameState
            get() = current

        override fun dispatchDetailed(event: GameEvent): DispatchResult {
            val result = GameReducer.apply(current, event)
            when (result) {
                is DispatchResult.Rejected -> Unit
                else -> current = result.state
            }
            return result
        }
    }

    @Test
    fun concurrentSubmitAllTakeEffectWithoutLoss() {
        val ledger = FakeLedger()
        val actor = SessionActor(ledger)
        // 锁定数量账（UNKNOWN：cards 必须全零），留出充足守恒余量。
        val lockResult = actor.post(LedgerCommand.LockHand(RankCounts.zero(), 1, RankCompleteness.UNKNOWN))
        assertTrue(lockResult is DispatchResult.Accepted)

        val n = 32
        val fire = CountDownLatch(1)
        val done = CountDownLatch(n)
        val results = Collections.synchronizedList(mutableListOf<DispatchResult>())
        val pool: ExecutorService = Executors.newFixedThreadPool(n)
        repeat(n) { index ->
            pool.execute {
                fire.await()
                actor.submit(
                    LedgerCommand.AcceptOutsidePlay(
                        RankCounts.zero(), 1, OutsideSeat.TOP,
                        RankCompleteness.PARTIAL, "evidence-$index"
                    )
                ) { result ->
                    results.add(result)
                    done.countDown()
                }
            }
        }
        fire.countDown()
        // 无丢失：n 个命令全部执行并回调。
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        actor.shutdown()

        // 全部被接受（单写者串行累加，守恒 1 + 32 <= 198）。
        assertEquals(n, results.size)
        results.forEach { result ->
            assertTrue("意外结果: $result", result is DispatchResult.AcceptedQuantityOnly)
        }
        // 账本无丢失：每个 evidenceId 恰好入账一次。
        val finalState = ledger.state
        assertEquals(n, finalState.outsidePlayedCount)
        val ids = finalState.events.filterIsInstance<GameEvent.OutsideCardsPlayed>().map { it.id }
        assertEquals(n, ids.size)
        assertEquals(n, ids.toSet().size)
    }

    @Test
    fun duplicateEvidenceIdIsIgnoredExactlyOnce() {
        val ledger = FakeLedger()
        val actor = SessionActor(ledger)
        actor.post(LedgerCommand.LockHand(RankCounts.zero(), 1, RankCompleteness.UNKNOWN))

        val command = LedgerCommand.AcceptOutsidePlay(
            RankCounts.zero(), 1, OutsideSeat.TOP, RankCompleteness.PARTIAL, "dup"
        )
        assertTrue(actor.post(command) is DispatchResult.AcceptedQuantityOnly)
        // 同一 evidenceId 重试：幂等，不重复扣牌。
        assertTrue(actor.post(command) is DispatchResult.DuplicateIgnored)
        assertEquals(1, ledger.state.outsidePlayedCount)
        actor.shutdown()
    }

    @Test
    fun pausedRejectsAutomaticPlayAndExplicitResumeRestores() {
        val ledger = FakeLedger()
        val actor = SessionActor(ledger)
        actor.post(LedgerCommand.LockHand(RankCounts.zero(), 1, RankCompleteness.UNKNOWN))

        // 暂停。
        assertTrue(actor.post(LedgerCommand.Pause(PauseReason.RANK_UNSTABLE)) is DispatchResult.Accepted)
        assertEquals(SessionPhase.PAUSED, ledger.state.phase)

        // PAUSED 拒绝自动出牌事件：数量不得变化。
        val rejected = actor.post(
            LedgerCommand.AcceptOutsidePlay(
                RankCounts.zero(), 1, OutsideSeat.TOP, RankCompleteness.PARTIAL, "while-paused"
            )
        )
        assertTrue("PAUSED 必须拒绝自动事件，实际: $rejected", rejected is DispatchResult.Rejected)
        assertEquals(0, ledger.state.outsidePlayedCount)

        // 显式 Resume 回到 ACTIVE，之后出牌重新被接受。
        assertTrue(actor.post(LedgerCommand.Resume) is DispatchResult.Accepted)
        assertEquals(SessionPhase.ACTIVE, ledger.state.phase)
        assertTrue(
            actor.post(
                LedgerCommand.AcceptOutsidePlay(
                    RankCounts.zero(), 1, OutsideSeat.TOP, RankCompleteness.PARTIAL, "after-resume"
                )
            ) is DispatchResult.AcceptedQuantityOnly
        )
        assertEquals(1, ledger.state.outsidePlayedCount)

        // 已处于 ACTIVE 时再 Resume 被拒。
        assertTrue(actor.post(LedgerCommand.Resume) is DispatchResult.Rejected)
        actor.shutdown()
    }

    @Test
    fun resumeRejectedOutsidePausedPhase() {
        val ledger = FakeLedger()
        val actor = SessionActor(ledger)
        // PRE_PLAY 状态直接 Resume：必须 Rejected。
        assertTrue(actor.post(LedgerCommand.Resume) is DispatchResult.Rejected)
        assertEquals(SessionPhase.PRE_PLAY, ledger.state.phase)
        actor.shutdown()
    }
}
