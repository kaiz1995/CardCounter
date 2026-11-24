package com.example.cardcounter.storage

import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.OutsideSeat
import com.example.cardcounter.domain.PauseReason
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCompleteness
import com.example.cardcounter.domain.RankCounts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionedEventLogTest {
    /** 走 reducer 构造一份含新字段与控制事件的合法状态。 */
    private fun stateWithAllEventKinds(): GameState {
        val events = listOf(
            GameEvent.NewSession("s"),
            GameEvent.SelfHandLocked(
                RankCounts.of(mapOf(Rank.ACE to 3)), "lock",
                cardCount = 5, rankCompleteness = RankCompleteness.PARTIAL
            ),
            GameEvent.OutsideCardsPlayed(
                RankCounts.of(mapOf(Rank.KING to 2)), "p", OutsideSeat.TOP,
                cardCount = 4, rankCompleteness = RankCompleteness.UNKNOWN
            ),
            GameEvent.SelfCardsPlayed(
                RankCounts.of(mapOf(Rank.TEN to 2)), "self", cardCount = 2,
                rankCompleteness = RankCompleteness.PARTIAL
            ),
            GameEvent.SessionPaused(PauseReason.RANK_UNSTABLE, "pause"),
            GameEvent.SessionResumed("resume")
        )
        return GameReducer.replay(events)
    }

    @Test
    fun encodeDecodeRoundTripKeepsAllFields() {
        val state = stateWithAllEventKinds()

        val json = VersionedEventLog.encode(state)
        val decoded = VersionedEventLog.decode(json)

        // seq = 事件数
        assertEquals(state.events.size, decoded?.first)
        // 事件完整往返（含 rankCompleteness 与 SessionResumed）
        assertEquals(state.events, decoded?.second)
        // 结构校验：schema=2、seq 在包装层
        val obj = JSONObject(json)
        assertEquals(2, obj.getInt("schema"))
        assertEquals(state.events.size, obj.getInt("seq"))
    }

    @Test
    fun legacyBareArrayIsNotVersionedFormat() {
        // 旧格式：裸事件数组，无 schema/seq 包装。
        val legacy = JSONArray()
            .put(
                JSONObject()
                    .put("id", "old")
                    .put("type", "OutsideCardsPlayed")
                    .put("cards", JSONObject().put(Rank.ACE.name, 2))
                    .put("cardCount", 2)
            )
            .toString()

        assertNull(VersionedEventLog.decode(legacy))
        // 旧格式 / 空磁盘视为 seq=0，可写入。
        val state = GameReducer.newSession()
        assertTrue(VersionedEventLog.saveIfCurrent(legacy, 0, state) is SaveResult.Ok)
        assertTrue(VersionedEventLog.saveIfCurrent(null, 0, state) is SaveResult.Ok)
    }

    @Test
    fun staleVersionWriteIsRejected() {
        val oldState = stateWithAllEventKinds()
        val oldJson = VersionedEventLog.encode(oldState)
        // 新状态多一个暂停事件：磁盘快照落后于本地。
        val newState = GameReducer.apply(
            oldState,
            GameEvent.SessionPaused(PauseReason.CAPTURE_INTERRUPTED, "pause-2")
        ).state

        // 旧版本写入（expectedSeq < 磁盘 seq）必须被拒绝。
        assertEquals(
            SaveResult.StaleRejected,
            VersionedEventLog.saveIfCurrent(oldJson, oldState.events.size - 1, newState)
        )
        // 当前版本写入允许，输出即新状态快照。
        val result = VersionedEventLog.saveIfCurrent(oldJson, oldState.events.size, newState)
        assertTrue(result is SaveResult.Ok)
        assertEquals(VersionedEventLog.encode(newState), (result as SaveResult.Ok).json)
    }

    @Test
    fun corruptJsonReturnsNull() {
        assertNull(VersionedEventLog.decode("{broken json"))
        // 缺 events 字段
        assertNull(VersionedEventLog.decode(JSONObject().put("schema", 2).put("seq", 1).toString()))
        // 缺 schema 字段
        assertNull(VersionedEventLog.decode(JSONObject().put("seq", 1).put("events", JSONArray()).toString()))
    }
}
