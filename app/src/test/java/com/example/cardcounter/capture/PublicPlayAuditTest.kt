package com.example.cardcounter.capture

import com.example.cardcounter.domain.Rank
import org.junit.Assert.assertEquals
import org.junit.Test

/** 手牌识别用的模板匹配去重（与出牌区无关）。 */
class PublicPlayAuditTest {
    @Test
    fun keepsOverlappingPublicCardsSeparate() {
        val matches = listOf(
            HandRankMatcher.Match(100, Rank.SEVEN, 1.0),
            HandRankMatcher.Match(114, Rank.SEVEN, 0.9)
        )

        assertEquals(2, selectDistinctMatches(matches, maxCount = 12, minGap = 10).size)
    }
}
