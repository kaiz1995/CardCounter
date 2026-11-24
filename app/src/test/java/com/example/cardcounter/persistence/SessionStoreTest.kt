package com.example.cardcounter.persistence

import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.OutsideSeat
import com.example.cardcounter.domain.PauseReason
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.domain.SessionPhase
import com.example.cardcounter.storage.VersionedEventLog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStoreTest {
    @Test
    fun trailingPauseSurvivesReplayOnRestart() {
        // load 即 replay：暂停事件保留在日志尾部时，重启后必须原样恢复 PAUSED。
        val events = listOf(
            GameEvent.NewSession("start"),
            GameEvent.HandConfirmed(RankCounts.of(mapOf(Rank.ACE to 3)), 3, "hand"),
            GameEvent.SessionPaused(PauseReason.RANK_UNSTABLE, "pause")
        )
        val state = GameReducer.replay(events)

        assertEquals(SessionPhase.PAUSED, state.phase)
        assertEquals(PauseReason.RANK_UNSTABLE, state.pauseReason)
        assertEquals(events, state.events)
    }

    @Test
    fun conservationPauseSurvivesStoredRoundTripOnRestart() {
        // 守恒失败产生的暂停同样不可自动解除：存盘再恢复仍是 PAUSED。
        val events = listOf(
            GameEvent.NewSession("start"),
            GameEvent.SessionPaused(PauseReason.CONSERVATION_FAILURE, "pause")
        )
        val stored = VersionedEventLog.encode(GameReducer.replay(events))

        val (restored, _) = VersionedEventLog.restoreOrNew(stored)

        assertEquals(SessionPhase.PAUSED, restored.phase)
        assertEquals(PauseReason.CONSERVATION_FAILURE, restored.pauseReason)
    }

    @Test
    fun eventJsonRoundTripKeepsAllEventKinds() {
        val events = listOf(
            GameEvent.NewSession("s"),
            GameEvent.HandConfirmed(RankCounts.of(mapOf(Rank.ACE to 3)), 3, "h"),
            GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.KING to 2)), OutsideSeat.TOP, "p"),
            GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.ACE to 1)), OutsideSeat.RIGHT_BOTTOM, "p2"),
            GameEvent.Reverted("p", "r"),
            GameEvent.SessionPaused(PauseReason.CONSERVATION_FAILURE, "pause"),
            GameEvent.SessionResumed("resume"),
            GameEvent.SessionClosed("close")
        )
        val array = JSONArray()
        events.forEach { array.put(VersionedEventLog.encodeEvent(it)) }
        val decoded = buildList {
            for (index in 0 until array.length()) {
                add(VersionedEventLog.decodeEvent(array.getJSONObject(index)))
            }
        }

        assertEquals(events, decoded)
    }

    @Test
    fun previousVersionStoredSessionRestartsAsFreshSession() {
        // 上一版存下的会话（含已删除的事件类型）：直接丢弃，开新局，不崩。
        val previous = JSONObject()
            .put("schema", 2)
            .put("seq", 2)
            .put(
                "events",
                JSONArray()
                    .put(JSONObject().put("id", "s").put("type", "NewSession"))
                    .put(
                        JSONObject().put("id", "lock").put("type", "SelfHandLocked")
                            .put("cards", JSONObject().put(Rank.ACE.name, 3))
                            .put("cardCount", 3).put("rankCompleteness", "COMPLETE")
                    )
            )
            .toString()

        val (state, seq) = VersionedEventLog.restoreOrNew(previous)

        assertEquals(0, seq)
        assertEquals(SessionPhase.ACTIVE, state.phase)
        assertFalse(state.handConfirmed)
        assertEquals(1, state.events.size)
        assertTrue(state.events.single() is GameEvent.NewSession)
    }
}
