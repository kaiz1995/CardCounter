package com.example.cardcounter.persistence

import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.PauseReason
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStoreTest {
    @Test
    fun removesOnlyTrailingRecoverablePause() {
        val start = GameEvent.NewSession("start")
        val pause = GameEvent.SessionPaused(PauseReason.RANK_UNSTABLE, "pause")
        val baselinePause = GameEvent.SessionPaused(PauseReason.BASELINE_FAILURE, "baseline")

        assertEquals(listOf(start), listOf(start, pause).withoutRecoverablePause())
        assertEquals(listOf(start), listOf(start, baselinePause).withoutRecoverablePause())
        assertEquals(listOf(start), listOf(start).withoutRecoverablePause())
    }
}
