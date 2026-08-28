package com.example.cardcounter.capture

import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayZoneConfirmTest {

    @Test
    fun fastConfirmAllowsOnlyHighConfidenceSmallPlays() {
        assertTrue(canFastConfirmPlay(RankCounts.of(mapOf(Rank.JACK to 1)), 0.88f))
        assertFalse(canFastConfirmPlay(RankCounts.of(mapOf(Rank.KING to 2)), 0.84f))
        assertTrue(canFastConfirmPlay(RankCounts.of(mapOf(Rank.ACE to 3)), 0.65f))
        assertFalse(canFastConfirmPlay(RankCounts.of(mapOf(Rank.ACE to 3)), 0.55f))
    }
}
