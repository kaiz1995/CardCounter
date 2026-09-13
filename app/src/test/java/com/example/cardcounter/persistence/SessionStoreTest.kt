package com.example.cardcounter.persistence

import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.PauseReason
import com.example.cardcounter.domain.RankCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /**
     * 账本类事件（出牌、锁牌、会话边界）必须立即落盘：丢掉会直接破坏
     * `OutsideRemaining = 198 - SelfLockedHand - OutsidePlayed` 守恒。
     */
    @Test
    fun ledgerEventsAlwaysWriteImmediately() {
        val lastWriteAt = 1_000L
        val justWrote = lastWriteAt + 1L

        val ledgerEvents = listOf(
            GameEvent.NewSession("new"),
            GameEvent.SessionClosed("closed"),
            GameEvent.SessionPaused(PauseReason.CORRUPTED_SESSION, "paused"),
            GameEvent.SelfHandLocked(RankCounts.zero(), "locked", cardCount = 0),
            GameEvent.SelfCardsPlayed(RankCounts.zero(), "self-played", cardCount = 0),
            GameEvent.OutsideCardsPlayed(RankCounts.zero(), "outside-played", cardCount = 0)
        )

        ledgerEvents.forEach { event ->
            assertTrue(
                "${event.javaClass.simpleName} 必须绕过节流立即落盘",
                SessionStore.shouldWriteImmediately(event, justWrote, lastWriteAt)
            )
        }
    }

    /** 撤销是纠错事件：节流窗口内可延后，超过阈值后仍会落盘。 */
    @Test
    fun revertedEventIsThrottledUntilWindowElapses() {
        val lastWriteAt = 1_000L
        val reverted = GameEvent.Reverted("target", "revert")

        assertFalse(SessionStore.shouldWriteImmediately(reverted, lastWriteAt + 1L, lastWriteAt))
        assertFalse(SessionStore.shouldWriteImmediately(reverted, lastWriteAt + 499L, lastWriteAt))
        assertTrue(SessionStore.shouldWriteImmediately(reverted, lastWriteAt + 500L, lastWriteAt))
        assertEquals(500L, SessionStore.WRITE_THROTTLE_MS)
    }

    /**
     * 迁移决定：磁盘日志来自「正向累计版」之前（无标记）时必须整体替换成新会话，
     * 否则旧模型事件会被新归约器 replay 出错误账本。已打标记则原样沿用。
     */
    @Test
    fun migrationReplacesLegacySessionOnlyOnce() {
        val legacy = GameReducer.newSession()

        val firstBoot = positiveCountSessionBootstrap(markerPresent = false, stored = legacy)
        assertTrue("首次进入正向累计版必须迁移", firstBoot.migrated)

        val secondBoot = positiveCountSessionBootstrap(markerPresent = true, stored = legacy)
        assertFalse("已打过标记不得重复迁移", secondBoot.migrated)
        assertEquals(legacy, secondBoot.state)

        val emptyDisk = positiveCountSessionBootstrap(markerPresent = true, stored = null)
        assertFalse(emptyDisk.migrated)
        assertTrue(emptyDisk.state.events.isNotEmpty())
    }
}
