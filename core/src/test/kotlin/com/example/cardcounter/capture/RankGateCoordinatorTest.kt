package com.example.cardcounter.capture

import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

/**
 * 跨线程开局门竞态回归测试：覆盖“旧局信号或旧局出牌在新局重置之后到达/执行，不得打开新局开局门或污染 skipped”。
 */
class RankGateCoordinatorTest {
    private val king = RankCounts.of(mapOf(Rank.KING to 1))

    @Test
    fun staleZoneSignalArrivingAfterNewGameResetNeverOpensNewOpeningGate() {
        val rankQueue = ArrayDeque<() -> Unit>()
        val coordinator = RankGateCoordinator { task ->
            rankQueue.addLast(task)
            true
        }

        val gen1 = coordinator.nextGeneration()
        coordinator.postReset(gen1, alreadyInPlay = false)
        while (rankQueue.isNotEmpty()) rankQueue.removeFirst().invoke()

        // 旧局在 gen1 发出了圆标信号，但还没在 rankThread 执行时，用户点击开新局。
        var lockedGen: Long? = null
        var replayed = 0
        coordinator.postZoneSignals(
            expectedGeneration = gen1,
            zone = PlayZone.RIGHT_BOTTOM,
            signals = ZoneSignals(clearedRose = false, turned = true, longCleared = false),
            nowMs = 1_000L,
            isOnTable = { false },
            onOpened = { lockedGen = gen1 },
            onReplay = { replayed += it.size }
        )

        // 调用线程立即递增代数并把新局重置排队到 rankThread。
        val gen2 = coordinator.nextGeneration()
        coordinator.postReset(gen2, alreadyInPlay = false)

        // rankThread 顺序排干队列：旧局信号不得打开新局开局门，也不得触发锁定或补记。
        while (rankQueue.isNotEmpty()) rankQueue.removeFirst().invoke()

        assertFalse("旧局信号不得打开新局开局门", coordinator.openingPlayGate.isReady())
        assertEquals(null, lockedGen)
        assertEquals(0, replayed)
    }

    @Test
    fun resetOnRankThreadRecoversEvenIfOldSignalRanJustBeforeGenerationBump() {
        val rankQueue = ArrayDeque<() -> Unit>()
        val coordinator = RankGateCoordinator { task ->
            rankQueue.addLast(task)
            true
        }

        val gen1 = coordinator.nextGeneration()
        coordinator.postReset(gen1, alreadyInPlay = false)
        while (rankQueue.isNotEmpty()) rankQueue.removeFirst().invoke()

        // 模拟旧局信号已在 gen1 时进入并先于新局 reset 跑在 rankThread 上：
        // 随后新局 reset 在 rankThread 串行执行，必须把门重新关上（不会出现旧局先于 reset 把门改完又被旧 reset 漏掉）。
        coordinator.postZoneSignals(
            expectedGeneration = gen1,
            zone = PlayZone.TOP,
            signals = ZoneSignals(clearedRose = false, turned = true, longCleared = false),
            nowMs = 1_000L,
            isOnTable = { false },
            onOpened = { },
            onReplay = { }
        )
        rankQueue.removeFirst().invoke()
        assertTrue(coordinator.openingPlayGate.isReady())

        val gen2 = coordinator.nextGeneration()
        coordinator.postReset(gen2, alreadyInPlay = false)
        while (rankQueue.isNotEmpty()) rankQueue.removeFirst().invoke()

        assertFalse("新局 reset 在 rankThread 串行执行后，开局门必须关闭", coordinator.openingPlayGate.isReady())
    }

    @Test
    fun staleFinalizedPlayDoesNotPolluteNewGameOpeningGateSkipped() {
        val rankQueue = ArrayDeque<() -> Unit>()
        val coordinator = RankGateCoordinator { task ->
            rankQueue.addLast(task)
            true
        }

        val gen1 = coordinator.nextGeneration()
        coordinator.postReset(gen1, alreadyInPlay = false)
        while (rankQueue.isNotEmpty()) rankQueue.removeFirst().invoke()

        // 新局重置发生（例如旧局 finalizeRoiEvent 正在做 YOLO 推理期间）。
        val gen2 = coordinator.nextGeneration()
        coordinator.postReset(gen2, alreadyInPlay = false)
        while (rankQueue.isNotEmpty()) rankQueue.removeFirst().invoke()

        var skippedCalled = false
        var admittedCalled = false
        coordinator.handleFinalizedPlay(
            expectedGeneration = gen1,
            zone = PlayZone.LEFT_TOP,
            cards = king,
            sampleTimeMs = 2_000L,
            onPrePlaySkipped = { skippedCalled = true },
            onAdmit = { admittedCalled = true }
        )
        assertFalse(skippedCalled)
        assertFalse(admittedCalled)

        // 新局后续圆标打开时，绝不能把旧局的手牌当成 skipped 补记出来。
        val retries = coordinator.openingPlayGate.onOutsideTurn(turned = true, nowMs = 3_000L) { true }
        assertTrue("旧局事件不得写进新局 openingPlayGate.skipped", retries.isEmpty())
    }
}
