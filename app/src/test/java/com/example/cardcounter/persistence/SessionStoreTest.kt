package com.example.cardcounter.persistence

import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.OutsideSeat
import com.example.cardcounter.domain.PauseReason
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCompleteness
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.domain.SessionPhase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStoreTest {
    @Test
    fun trailingPauseSurvivesReplayOnRestart() {
        // load 即 replay：暂停事件保留在日志尾部时，重启后必须原样恢复 PAUSED。
        val events = listOf(
            GameEvent.NewSession("start"),
            GameEvent.SelfHandLocked(RankCounts.of(mapOf(Rank.ACE to 3)), "lock"),
            GameEvent.SessionPaused(PauseReason.RANK_UNSTABLE, "pause")
        )
        val state = GameReducer.replay(events)

        assertEquals(SessionPhase.PAUSED, state.phase)
        assertEquals(PauseReason.RANK_UNSTABLE, state.pauseReason)
        assertEquals(events, state.events)
    }

    @Test
    fun conservationPauseSurvivesReplayOnRestart() {
        // 守恒失败产生的暂停同样不可自动解除。
        val events = listOf(
            GameEvent.NewSession("start"),
            GameEvent.SelfHandLocked(RankCounts.zero(), "lock", cardCount = 198),
            GameEvent.SessionPaused(PauseReason.CONSERVATION_FAILURE, "pause")
        )
        val state = GameReducer.replay(events)

        assertEquals(SessionPhase.PAUSED, state.phase)
        assertEquals(PauseReason.CONSERVATION_FAILURE, state.pauseReason)
    }

    @Test
    fun eventJsonRoundTripKeepsNewFields() {
        val events = listOf(
            GameEvent.NewSession("s"),
            GameEvent.SelfHandLocked(
                RankCounts.of(mapOf(Rank.ACE to 3)), "h",
                cardCount = 5, rankCompleteness = RankCompleteness.PARTIAL
            ),
            GameEvent.OutsideCardsPlayed(
                RankCounts.of(mapOf(Rank.KING to 2)), "p", null, 4,
                RankCompleteness.UNKNOWN
            ),
            GameEvent.OutsideCardsPlayed(
                RankCounts.of(mapOf(Rank.ACE to 1)), "p2", OutsideSeat.TOP, 1
            ),
            GameEvent.SelfCardsPlayed(
                RankCounts.of(mapOf(Rank.TEN to 2)), "sp", 2,
                RankCompleteness.PARTIAL
            ),
            GameEvent.Reverted("p", "r"),
            GameEvent.SessionPaused(PauseReason.CONSERVATION_FAILURE, "pause"),
            GameEvent.SessionClosed("close")
        )
        val array = JSONArray()
        events.forEach { array.put(EventJsonCodec.encodeEvent(it)) }
        val decoded = buildList {
            for (index in 0 until array.length()) {
                add(EventJsonCodec.decodeEvent(array.getJSONObject(index)))
            }
        }

        assertEquals(events, decoded)
    }

    @Test
    fun legacyJsonWithoutCompletenessDefaultsToComplete() {
        // 旧数据没有 rankCompleteness 字段，必须按 COMPLETE 解码。
        val legacy = JSONObject()
            .put("id", "old")
            .put("type", "OutsideCardsPlayed")
            .put("cards", JSONObject().put(Rank.ACE.name, 2))
            .put("cardCount", 2)
        val event = EventJsonCodec.decodeEvent(legacy) as GameEvent.OutsideCardsPlayed

        assertEquals(RankCompleteness.COMPLETE, event.rankCompleteness)
        assertEquals(2, event.cardCount)
    }
}
