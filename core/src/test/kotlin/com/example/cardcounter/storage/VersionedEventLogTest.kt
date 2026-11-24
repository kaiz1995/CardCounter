package com.example.cardcounter.storage

import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.GameState
import com.example.cardcounter.domain.OutsideSeat
import com.example.cardcounter.domain.PauseReason
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.domain.SessionPhase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionedEventLogTest {
    /** 走 reducer 构造一份含全部事件种类的合法状态。 */
    private fun stateWithAllEventKinds(): GameState {
        val events = listOf(
            GameEvent.NewSession("s"),
            GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.KING to 2)), OutsideSeat.TOP, "p"),
            GameEvent.HandConfirmed(RankCounts.of(mapOf(Rank.ACE to 3)), 3, "hand"),
            GameEvent.OutsideCardsPlayed(RankCounts.of(mapOf(Rank.TEN to 1)), OutsideSeat.LEFT_BOTTOM, "p2"),
            GameEvent.Reverted("p2", "rv"),
            GameEvent.SessionPaused(PauseReason.RANK_UNSTABLE, "pause"),
            GameEvent.SessionResumed("resume")
        )
        return GameReducer.replay(events)
    }

    @Test
    fun encodeDecodeRoundTripKeepsAllFields() {
        val state = stateWithAllEventKinds()
        assertEquals(7, state.events.size)

        val json = VersionedEventLog.encode(state)
        val decoded = VersionedEventLog.decode(json)

        assertEquals(state.events.size, decoded?.first)
        assertEquals(state.events, decoded?.second)
        val obj = JSONObject(json)
        assertEquals(3, obj.getInt("schema"))
        assertEquals(state.events.size, obj.getInt("seq"))
    }

    @Test
    fun restoreOrNewReplaysCurrentVersion() {
        val state = stateWithAllEventKinds()

        val (restored, seq) = VersionedEventLog.restoreOrNew(VersionedEventLog.encode(state))

        assertEquals(state, restored)
        assertEquals(state.events.size, seq)
        assertTrue(restored.handConfirmed)
    }

    @Test
    fun oldVersionSessionIsDiscardedAndFreshSessionStarts() {
        // 上一版（schema=2）存下的会话，含已被删除的事件类型。
        val oldSchema = JSONObject()
            .put("schema", 2)
            .put("seq", 3)
            .put(
                "events",
                JSONArray()
                    .put(JSONObject().put("id", "s").put("type", "NewSession"))
                    .put(
                        JSONObject().put("id", "lock").put("type", "SelfHandLocked")
                            .put("cards", JSONObject().put(Rank.ACE.name, 3))
                            .put("cardCount", 3).put("rankCompleteness", "COMPLETE")
                    )
                    .put(
                        JSONObject().put("id", "p").put("type", "OutsideCardsPlayed")
                            .put("cards", JSONObject().put(Rank.KING.name, 2))
                            .put("cardCount", 2).put("rankCompleteness", "COMPLETE")
                    )
            )
            .toString()
        // 更早的裸事件数组格式。
        val bareArray = JSONArray()
            .put(JSONObject().put("id", "old").put("type", "NewSession"))
            .toString()
        // 版本号对但事件类型已不存在：整份判为不可用。
        val currentSchemaOldEvent = JSONObject()
            .put("schema", VersionedEventLog.SCHEMA)
            .put("seq", 1)
            .put("events", JSONArray().put(JSONObject().put("id", "x").put("type", "SelfCardsPlayed")))
            .toString()

        for (stored in listOf(oldSchema, bareArray, currentSchemaOldEvent, "{broken json", null)) {
            assertNull("旧版本/损坏数据不应被解码: $stored", stored?.let(VersionedEventLog::decode))
            val (state, seq) = VersionedEventLog.restoreOrNew(stored)

            assertEquals(0, seq)
            assertEquals(SessionPhase.ACTIVE, state.phase)
            assertFalse(state.handConfirmed)
            assertEquals(1, state.events.size)
            assertTrue(state.events.single() is GameEvent.NewSession)
            assertEquals(0, state.outsidePlayedCount)
            assertNull(state.pauseReason)
        }
    }

    @Test
    fun oldDataIsOverwritableAndEmptyDiskIsWritable() {
        val oldSchema = JSONObject().put("schema", 2).put("seq", 5).put("events", JSONArray()).toString()
        val state = GameReducer.newSession()

        // 旧版本 / 空磁盘按 seq=0 处理，新会话可以直接覆盖写入。
        assertTrue(VersionedEventLog.saveIfCurrent(oldSchema, 0, state) is SaveResult.Ok)
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

        assertEquals(
            SaveResult.StaleRejected,
            VersionedEventLog.saveIfCurrent(oldJson, oldState.events.size - 1, newState)
        )
        val result = VersionedEventLog.saveIfCurrent(oldJson, oldState.events.size, newState)
        assertTrue(result is SaveResult.Ok)
        assertEquals(VersionedEventLog.encode(newState), (result as SaveResult.Ok).json)
    }

    @Test
    fun corruptJsonReturnsNull() {
        assertNull(VersionedEventLog.decode("{broken json"))
        // 缺 events 字段
        assertNull(VersionedEventLog.decode(JSONObject().put("schema", 3).put("seq", 1).toString()))
        // 缺 schema 字段
        assertNull(VersionedEventLog.decode(JSONObject().put("seq", 1).put("events", JSONArray()).toString()))
    }
}
