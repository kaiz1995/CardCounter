package com.example.cardcounter.capture

import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import org.junit.Assert.assertEquals
import org.junit.Test

/** 依赖安卓端问题样本与模板匹配代码的出牌诊断用例（原在 StablePlayGateTest）。 */
class PublicPlayAuditTest {
    @Test
    fun aceAuditMetadataContainsFusionFields() {
        val ace = RankCounts.of(mapOf(Rank.ACE to 1))
        val metadata = aceAuditMetadata(PlayZone.LEFT_BOTTOM, 3, 42, ace,
            listOf(RankCountsCandidate(ace, .8f, ace, ace, 1)))
        val candidate = metadata.candidates.single()
        assertEquals("LEFT_BOTTOM", metadata.zone); assertEquals(3, metadata.generation); assertEquals(42, metadata.timeMs)
        assertEquals(ace, candidate.fusedCards); assertEquals(ace, candidate.yoloCards)
        assertEquals(ace, candidate.templateCards); assertEquals(.8f, candidate.minimumConfidence)
        assertEquals(1, candidate.cardCount)
        assertEquals(1, rankCountsMap(metadata.winner)["ACE"])
        assertEquals(1, rankCountsMap(candidate.fusedCards)["ACE"])
    }
    @Test
    fun keepsOverlappingPublicCardsSeparate() {
        val matches = listOf(
            HandRankMatcher.Match(100, Rank.SEVEN, 1.0),
            HandRankMatcher.Match(114, Rank.SEVEN, 0.9)
        )

        assertEquals(2, selectDistinctMatches(matches, maxCount = 12, minGap = 10).size)
    }
}
