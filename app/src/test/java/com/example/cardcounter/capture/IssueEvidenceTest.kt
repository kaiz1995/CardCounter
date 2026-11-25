package com.example.cardcounter.capture

import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.vision.YoloDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 停机和被拒时存下来的证据：要带上原因、最终结果，以及每帧候选和每个检测框。 */
class IssueEvidenceTest {
    private val nine = RankCounts.of(mapOf(Rank.NINE to 1))
    private val jack = RankCounts.of(mapOf(Rank.JACK to 1))

    @Test
    fun evidenceKeepsReasonResultAndPerFrameCandidates() {
        val candidates = listOf(
            RankCountsCandidate(nine, .9f, listOf(YoloDetection(Rank.NINE, "NINE", .9f, 160f, 150f, 40f, 60f))),
            RankCountsCandidate(jack, .5f, emptyList())
        )

        val metadata = evidenceMetadata(PlayZone.RIGHT_BOTTOM, "PAUSED_CONSERVATION_FAILURE", 3, 42, nine, candidates)

        assertEquals("RIGHT_BOTTOM", metadata.zone)
        assertEquals("PAUSED_CONSERVATION_FAILURE", metadata.reason)
        assertEquals(3, metadata.generation)
        assertEquals(42, metadata.timeMs)
        assertEquals(1, rankCountsMap(metadata.result!!)["NINE"])
        assertEquals(2, metadata.candidates.size)
        val first = metadata.candidates[0]
        assertEquals(1, rankCountsMap(first.cards)["NINE"])
        assertEquals(.9f, first.minimumConfidence)
        assertEquals(1, first.cardCount)
        val box = first.detections.single()
        assertEquals("NINE", box.rank)
        assertEquals(.9f, box.confidence)
        assertEquals(160f, box.centerX)
        assertEquals(150f, box.centerY)
        assertEquals(40f, box.width)
        assertEquals(60f, box.height)
        assertEquals(0, metadata.candidates[1].detections.size)
    }

    /** 张数对不上时确认的证据：没数出来的字段写显式 null（不能缺键），改过的点数原样写成数组。 */
    @Test
    fun handCountMismatchEvidenceWritesExplicitNullsAndEditedRanks() {
        val shown = RankCounts.of(mapOf(Rank.SEVEN to 2, Rank.THREE to 1))
        val template = RankCounts.of(mapOf(Rank.SEVEN to 1))

        val json = handCountMismatchJson(42, shown, 3, null, 17, null, null, template, listOf("SEVEN"))

        assertEquals(HAND_COUNT_MISMATCH, json.getString("reason"))
        assertEquals(42L, json.getLong("timeMs"))
        assertEquals(3, json.getInt("total"))
        assertEquals(2, json.getJSONObject("shown").getInt("SEVEN"))
        assertEquals(1, json.getJSONObject("template").getInt("SEVEN"))
        assertEquals(17, json.getInt("topRowCount"))
        listOf("positionCount", "bottomRowCount", "model").forEach { key ->
            assertTrue("$key 必须是显式 null", json.has(key) && json.isNull(key))
        }
        val edited = json.getJSONArray("edited")
        assertEquals(1, edited.length())
        assertEquals("SEVEN", edited.getString(0))
    }

    @Test
    fun noConsensusEvidenceHasNoResult() {
        val metadata = evidenceMetadata(PlayZone.TOP, "NO_CONSENSUS", 1, 7, null, emptyList())

        assertNull(metadata.result)
        assertEquals("NO_CONSENSUS", metadata.reason)
        assertEquals(0, metadata.candidates.size)
    }
}
