package com.example.cardcounter.recognition

import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoujiPlayRulesTest {
    private fun hand(vararg pairs: Pair<Rank, Int>): RankCounts =
        RankCounts.of(pairs.toMap())

    private fun assertSuspicious(expectedCode: String, cards: RankCounts) {
        val result = GoujiPlayRules.check(cards)
        assertTrue("预期 Suspicious($expectedCode)，实际为 $result", result is PlayRuleCheck.Suspicious)
        val suspicious = result as PlayRuleCheck.Suspicious
        assertEquals(expectedCode, suspicious.code)
        assertTrue("detail 不能为空", suspicious.detail.isNotBlank())
    }

    @Test
    fun realGroundTruthPlaysAreValid() {
        assertEquals(PlayRuleCheck.Valid, GoujiPlayRules.check(hand(Rank.FOUR to 1)))
        assertEquals(PlayRuleCheck.Valid, GoujiPlayRules.check(hand(Rank.QUEEN to 1)))
        assertEquals(PlayRuleCheck.Valid, GoujiPlayRules.check(hand(Rank.KING to 1)))
        assertEquals(
            PlayRuleCheck.Valid,
            GoujiPlayRules.check(hand(Rank.BIG_JOKER to 1, Rank.KING to 6))
        )
        assertEquals(
            PlayRuleCheck.Valid,
            GoujiPlayRules.check(hand(Rank.SMALL_JOKER to 1, Rank.SEVEN to 6))
        )
        assertEquals(PlayRuleCheck.Valid, GoujiPlayRules.check(hand(Rank.THREE to 1)))
        assertEquals(PlayRuleCheck.Valid, GoujiPlayRules.check(hand(Rank.SEVEN to 2)))
        assertEquals(PlayRuleCheck.Valid, GoujiPlayRules.check(hand(Rank.SIX to 6)))
    }

    @Test
    fun emptyHandsAreSuspicious() {
        assertSuspicious("EMPTY", RankCounts.zero())
        assertSuspicious("EMPTY", RankCounts.of(mapOf(Rank.ACE to 0, Rank.TWO to 0)))
    }

    @Test
    fun overInventoryHandsAreSuspicious() {
        assertSuspicious("OVER_INVENTORY", hand(Rank.BIG_JOKER to 5))
        assertSuspicious("OVER_INVENTORY", hand(Rank.THREE to 7))
        assertSuspicious("OVER_INVENTORY", hand(Rank.FOUR to 9))
        assertSuspicious("OVER_INVENTORY", hand(Rank.KING to 17, Rank.TWO to 1))
    }

    @Test
    fun mixedMainRanksAreSuspicious() {
        assertSuspicious("MIXED_RANKS", hand(Rank.SEVEN to 1, Rank.EIGHT to 1))
        assertSuspicious(
            "MIXED_RANKS",
            hand(Rank.KING to 6, Rank.ACE to 1, Rank.BIG_JOKER to 1)
        )
        assertSuspicious(
            "MIXED_RANKS",
            hand(Rank.THREE to 1, Rank.FOUR to 1, Rank.FIVE to 1)
        )
    }

    @Test
    fun wildOnlyHandsAreValid() {
        assertEquals(PlayRuleCheck.Valid, GoujiPlayRules.check(hand(Rank.BIG_JOKER to 1)))
        assertEquals(PlayRuleCheck.Valid, GoujiPlayRules.check(hand(Rank.TWO to 2)))
        assertEquals(
            PlayRuleCheck.Valid,
            GoujiPlayRules.check(hand(Rank.BIG_JOKER to 1, Rank.SMALL_JOKER to 1))
        )
        assertEquals(
            PlayRuleCheck.Valid,
            GoujiPlayRules.check(hand(Rank.SMALL_JOKER to 1, Rank.TWO to 3))
        )
    }

    @Test
    fun wildRanksAndInventoryBoundaries() {
        assertEquals(
            setOf(Rank.BIG_JOKER, Rank.SMALL_JOKER, Rank.TWO),
            GoujiPlayRules.WILD_RANKS
        )
        assertEquals(
            PlayRuleCheck.Valid,
            GoujiPlayRules.check(
                hand(
                    Rank.THREE to Rank.THREE.initialCount,
                    Rank.BIG_JOKER to Rank.BIG_JOKER.initialCount,
                    Rank.SMALL_JOKER to Rank.SMALL_JOKER.initialCount,
                    Rank.TWO to Rank.TWO.initialCount
                )
            )
        )
    }
}
