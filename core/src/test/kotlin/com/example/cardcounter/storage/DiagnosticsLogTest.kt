package com.example.cardcounter.storage

import com.example.cardcounter.domain.DispatchResult
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.PauseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLogTest {
    @Test
    fun ringBufferEvictsOldestBeyondCapacity() {
        val log = DiagnosticsLog(clock = { 0L })
        repeat(250) { index ->
            log.record("e-$index", "TOP", "OUTSIDE", 1, 1, DiagnosticsLog.Decision.OBSERVED)
        }
        // 上限 200：最旧的 50 条被轮换淘汰。
        assertEquals(200, log.size())
        assertEquals("e-50", log.snapshot().first().evidenceId)
        assertEquals("e-249", log.snapshot().last().evidenceId)
    }

    @Test
    fun decisionVocabularyIsFixedAndCoversDispatchResults() {
        // 词表固定；没有“只记张数”这一档。
        assertEquals("OBSERVED", DiagnosticsLog.Decision.OBSERVED)
        assertEquals("STABLE", DiagnosticsLog.Decision.STABLE)
        assertEquals("ACCEPTED", DiagnosticsLog.Decision.ACCEPTED)
        assertEquals("REJECTED", DiagnosticsLog.Decision.REJECTED)
        assertEquals("RETRY", DiagnosticsLog.Decision.RETRY)
        assertEquals("PAUSED", DiagnosticsLog.Decision.PAUSED)
        assertEquals("DUPLICATE", DiagnosticsLog.Decision.DUPLICATE)

        // 派发结果 → 词表映射。
        val state = GameReducer.newSession()
        assertEquals(DiagnosticsLog.Decision.ACCEPTED, DiagnosticsLog.Decision.forResult(DispatchResult.Accepted(state)))
        assertEquals(DiagnosticsLog.Decision.DUPLICATE, DiagnosticsLog.Decision.forResult(DispatchResult.DuplicateIgnored(state)))
        assertEquals(DiagnosticsLog.Decision.REJECTED, DiagnosticsLog.Decision.forResult(DispatchResult.Rejected(state, "x")))
        assertEquals(DiagnosticsLog.Decision.PAUSED, DiagnosticsLog.Decision.forResult(DispatchResult.Paused(state, PauseReason.RANK_UNSTABLE)))
    }

    @Test
    fun jsonRoundTripKeepsOptionalFields() {
        val log = DiagnosticsLog(clock = { 42L })
        log.record("e1", "TOP", "OUTSIDE", quantity = 2, rankTotal = 2, decision = DiagnosticsLog.Decision.ACCEPTED, reason = "ok")
        // 数量未知：quantity/rankTotal 为 null，evidenceId 也允许为空。
        log.record(null, "SELF", "SELF", quantity = null, rankTotal = null, decision = DiagnosticsLog.Decision.STABLE)

        val restored = DiagnosticsLog(clock = { 0L })
        assertTrue(restored.loadRecentJson(log.toRecentJson()))
        assertEquals(log.snapshot(), restored.snapshot())
        assertEquals(42L, restored.snapshot().first().capturedAtMillis)
    }

    @Test
    fun corruptedDiagnosticsJsonFailsGracefully() {
        val log = DiagnosticsLog(clock = { 0L })
        log.record("e1", "TOP", "OUTSIDE", 1, 1, DiagnosticsLog.Decision.OBSERVED)
        // 损坏 JSON：恢复失败且不破坏现有缓冲。
        assertFalse(log.loadRecentJson("{broken"))
        assertEquals(1, log.size())
        assertEquals("e1", log.snapshot().first().evidenceId)
    }
}
