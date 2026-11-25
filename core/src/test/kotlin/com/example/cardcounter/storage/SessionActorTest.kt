package com.example.cardcounter.storage

import com.example.cardcounter.domain.DispatchResult
import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.OutsideSeat
import com.example.cardcounter.domain.PauseReason
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.domain.SessionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val king = RankCounts.of(mapOf(Rank.KING to 1))
    private val ace = RankCounts.of(mapOf(Rank.ACE to 1))
    private val two = RankCounts.of(mapOf(Rank.TWO to 1))

    @Test
    fun concurrentSubmitAllTakeEffectWithoutLoss() {
        val ledger = FakeLedger()
        val actor = SessionActor(ledger)
        assertTrue(actor.post(LedgerCommand.ConfirmHand(king, 1)) is DispatchResult.Accepted)

        val n = 32
        val fire = CountDownLatch(1)
        val done = CountDownLatch(n)
        val results = Collections.synchronizedList(mutableListOf<DispatchResult>())
        val pool: ExecutorService = Executors.newFixedThreadPool(n)
        repeat(n) { index ->
            pool.execute {
                fire.await()
                // A、2 各 16 张：32 手各 1 张刚好出满，不触发守恒。
                actor.submit(
                    LedgerCommand.AcceptOutsidePlay(
                        if (index % 2 == 0) ace else two, OutsideSeat.TOP, "evidence-$index"
                    )
                ) { result ->
                    results.add(result)
                    done.countDown()
                }
            }
        }
        fire.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        actor.shutdown()

        assertEquals(n, results.size)
        results.forEach { result ->
            assertTrue("意外结果: $result", result is DispatchResult.Accepted)
        }
        val finalState = ledger.state
        assertEquals(n, finalState.outsidePlayedCount)
        assertEquals(n, finalState.playedCountBy(OutsideSeat.TOP))
        val ids = finalState.events.filterIsInstance<GameEvent.OutsideCardsPlayed>().map { it.id }
        assertEquals(n, ids.size)
        assertEquals(n, ids.toSet().size)
    }

    @Test
    fun duplicateEvidenceIdIsIgnoredExactlyOnce() {
        val ledger = FakeLedger()
        val actor = SessionActor(ledger)

        val command = LedgerCommand.AcceptOutsidePlay(ace, OutsideSeat.TOP, "dup")
        assertTrue(actor.post(command) is DispatchResult.Accepted)
        // 同一 evidenceId 重试：幂等，不重复扣牌。
        assertTrue(actor.post(command) is DispatchResult.DuplicateIgnored)
        assertEquals(1, ledger.state.outsidePlayedCount)
        actor.shutdown()
    }

    @Test
    fun confirmHandGoesThroughActorOnlyOncePerSession() {
        val ledger = FakeLedger()
        val actor = SessionActor(ledger)
        val hand = RankCounts.of(mapOf(Rank.KING to 2, Rank.ACE to 1))

        // 点数合计为 0：被拒，状态不变。
        assertTrue(actor.post(LedgerCommand.ConfirmHand(RankCounts.zero(), null, "empty")) is DispatchResult.Rejected)
        assertFalse(ledger.state.handConfirmed)

        // 数出来的张数和点数合计对不上：照常确认，张数只作记录。
        assertTrue(actor.post(LedgerCommand.ConfirmHand(hand, 4, "ok")) is DispatchResult.Accepted)
        assertEquals(hand, ledger.state.selfHand)

        val again = actor.post(LedgerCommand.ConfirmHand(king, 1, "again"))
        assertTrue(again is DispatchResult.Rejected)
        assertEquals(hand, ledger.state.selfHand)
        actor.shutdown()
    }

    @Test
    fun pausedRejectsAutomaticPlayAndExplicitResumeRestores() {
        val ledger = FakeLedger()
        val actor = SessionActor(ledger)

        assertTrue(actor.post(LedgerCommand.Pause(PauseReason.RANK_UNSTABLE)) is DispatchResult.Accepted)
        assertEquals(SessionPhase.PAUSED, ledger.state.phase)

        // PAUSED 拒绝自动出牌事件：数量不得变化。
        val rejected = actor.post(LedgerCommand.AcceptOutsidePlay(ace, OutsideSeat.TOP, "while-paused"))
        assertTrue("PAUSED 必须拒绝自动事件，实际: $rejected", rejected is DispatchResult.Rejected)
        assertEquals(0, ledger.state.outsidePlayedCount)

        // 显式 Resume 回到 ACTIVE，之后出牌重新被接受。
        assertTrue(actor.post(LedgerCommand.Resume) is DispatchResult.Accepted)
        assertEquals(SessionPhase.ACTIVE, ledger.state.phase)
        assertTrue(
            actor.post(LedgerCommand.AcceptOutsidePlay(ace, OutsideSeat.TOP, "after-resume")) is
                DispatchResult.Accepted
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
        // 新会话本身就是 ACTIVE：直接 Resume 必须 Rejected。
        assertTrue(actor.post(LedgerCommand.Resume) is DispatchResult.Rejected)
        assertEquals(SessionPhase.ACTIVE, ledger.state.phase)
        actor.shutdown()
    }
}
