package com.example.cardcounter.vision

import com.example.cardcounter.domain.Rank
import org.junit.Assert.assertEquals
import org.junit.Test

class YoloModelContractTest {
    @Test
    fun original28LabelsNormalizeTo15Ranks() {
        assertEquals(28, YoloLabelMap.labels.size)
        assertEquals(Rank.ACE, YoloLabelMap.rank(0))
        assertEquals(Rank.ACE, YoloLabelMap.rank(13))
        assertEquals(Rank.BIG_JOKER, YoloLabelMap.rank(26))
        assertEquals(Rank.SMALL_JOKER, YoloLabelMap.rank(27))
        assertEquals(15, YoloLabelMap.labels.indices.map(YoloLabelMap::rank).toSet().size)
    }

    @Test
    fun nmsKeepsHighestConfidenceOverlappingDetection() {
        val high = YoloDetection(Rank.ACE, "RA", 0.9f, 20f, 20f, 10f, 10f)
        val low = high.copy(confidence = 0.7f, centerX = 21f)
        val separate = YoloDetection(Rank.KING, "RK", 0.8f, 80f, 80f, 10f, 10f)

        assertEquals(listOf(high, separate), nonMaxSuppression(listOf(low, separate, high), 0.45f))
    }
}
