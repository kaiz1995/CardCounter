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

    /** 确认手牌的证据：没有的字段写显式 null（不能缺键），改过的点数写成数组，模型每个检测框的 rank/conf/cx/cy/w/h 都在。 */
    @Test
    fun handEvidenceWritesExplicitNullsEditedRanksAndEveryModelBox() {
        val shown = RankCounts.of(mapOf(Rank.SEVEN to 2, Rank.THREE to 1))
        val box = YoloDetection(Rank.SEVEN, "SEVEN", .42f, 120.5f, 244.2f, 7.1f, 10.2f)

        val json = handEvidenceJson(
            HAND_EDITED, 42, shown, 3, null, 17, null, null, listOf(box), null, listOf("SEVEN"), "ACCEPTED", 9
        )

        assertEquals(HAND_EDITED, json.getString("reason"))
        assertEquals(42L, json.getLong("timeMs"))
        assertEquals(HandRegions.YOLO_TOP, json.getInt("areaTop"))
        assertEquals(3, json.getInt("total"))
        assertEquals(2, json.getJSONObject("shown").getInt("SEVEN"))
        assertEquals(17, json.getInt("topRowCount"))
        assertEquals("ACCEPTED", json.getString("outcome"))
        assertEquals(9L, json.getLong("draftVersion"))
        listOf("positionCount", "bottomRowCount", "model", "template").forEach { key ->
            assertTrue("$key 必须是显式 null", json.has(key) && json.isNull(key))
        }
        val edited = json.getJSONArray("edited")
        assertEquals(1, edited.length())
        assertEquals("SEVEN", edited.getString(0))
        val boxes = json.getJSONArray("modelDetections")
        assertEquals(1, boxes.length())
        val first = boxes.getJSONObject(0)
        assertEquals("SEVEN", first.getString("rank"))
        assertEquals(.42, first.getDouble("conf"), 0.001)
        assertEquals(120.5, first.getDouble("cx"), 0.01)
        assertEquals(244.2, first.getDouble("cy"), 0.01)
        assertEquals(7.1, first.getDouble("w"), 0.01)
        assertEquals(10.2, first.getDouble("h"), 0.01)
    }

    /** 三种确认证据的原因互不相同，目录和 meta 里能分开看。 */
    @Test
    fun handEvidenceReasonsAreSeparate() {
        assertEquals(
            3,
            setOf(HAND_COUNT_MISMATCH, HAND_EDITED, HAND_CONFIRM_FAILED).size
        )
        val template = RankCounts.of(mapOf(Rank.SEVEN to 1))
        val json = handEvidenceJson(
            HAND_CONFIRM_FAILED, 1, RankCounts.zero(), 0, 5, 3, 2, null, emptyList(), template, emptyList(),
            "REJECTED:本局手牌已确认，不能修改", 4
        )
        assertEquals(HAND_CONFIRM_FAILED, json.getString("reason"))
        assertEquals(1, json.getJSONObject("template").getInt("SEVEN"))
        assertEquals(0, json.getJSONArray("modelDetections").length())
    }

    /** 低把握手牌样本的 meta：带原因、单帧与合并结果、两份检测框，没数出来的张数是显式 null。 */
    @Test
    fun handLowConfidenceJsonKeepsTriggersCountsAndBoxes() {
        val low = YoloDetection(Rank.FOUR, "FOUR", .3f, 268.7f, 244.2f, 6.8f, 10f)
        val sure = YoloDetection(Rank.FOUR, "FOUR", .8f, 284.7f, 244.3f, 6.8f, 9.9f)
        val merged = RankCounts.of(mapOf(Rank.FOUR to 2))
        val result = HandMergeResult(
            single = RankCounts.of(mapOf(Rank.FOUR to 1)),
            merged = merged,
            frameDetections = listOf(sure),
            slotDetections = listOf(low, sure),
            frames = 2,
            fellBack = false
        )

        val json = handLowConfidenceJson(
            42, 7, 9, listOf(HandSampleTrigger.MERGE_GAIN), result, null, RankCounts.zero(), 870
        )

        assertEquals(HAND_LOW_CONFIDENCE, json.getString("reason"))
        assertEquals(HandSampleTrigger.MERGE_GAIN, json.getJSONArray("triggers").getString(0))
        assertEquals(7L, json.getLong("handRevision"))
        assertEquals(870, json.getInt("canvasSize"))
        assertEquals(320, json.getInt("modelInputSize"))
        assertEquals(1, json.getInt("singleTotal"))
        assertEquals(2, json.getInt("mergedTotal"))
        assertEquals(2, json.getJSONObject("merged").getInt("FOUR"))
        assertTrue("positionCount 必须是显式 null", json.has("positionCount") && json.isNull("positionCount"))
        assertEquals(870, json.getInt("canvasSize"))
        assertEquals(1, json.getJSONArray("frameDetections").length())
        val slots = json.getJSONArray("slotDetections")
        assertEquals(2, slots.length())
        assertEquals("FOUR", slots.getJSONObject(0).getString("rank"))
        assertEquals(.3, slots.getJSONObject(0).getDouble("conf"), 0.001)
        assertEquals(268.7, slots.getJSONObject(0).getDouble("cx"), 0.01)
    }

    @Test
    fun noConsensusEvidenceHasNoResult() {
        val metadata = evidenceMetadata(PlayZone.TOP, "NO_CONSENSUS", 1, 7, null, emptyList())

        assertNull(metadata.result)
        assertEquals("NO_CONSENSUS", metadata.reason)
        assertEquals(0, metadata.candidates.size)
    }
}
