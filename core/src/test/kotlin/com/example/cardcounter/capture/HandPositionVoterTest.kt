package com.example.cardcounter.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandPositionVoterTest {
    /** 同一画面里偶尔一帧数错，取多数，不跟着最新一帧走。 */
    @Test
    fun usesTheMostFrequentCountNotTheLatestFrame() {
        val voter = HandPositionVoter()
        assertEquals(16 to 17, voter.add(1, 16 to 17))
        assertEquals(16 to 17, voter.add(1, 16 to 17))
        assertEquals(16 to 17, voter.add(1, 17 to 17))
        assertEquals(16 to 17, voter.add(1, 16 to 16))
    }

    @Test
    fun tieGoesToTheMostRecentCount() {
        val voter = HandPositionVoter()
        voter.add(1, 16 to 17)
        assertEquals(17 to 17, voter.add(1, 17 to 17))
        assertEquals(16 to 17, voter.add(1, 16 to 17))
    }

    @Test
    fun framesWithoutACountDoNotVote() {
        val voter = HandPositionVoter()
        assertNull(voter.add(1, null))
        assertEquals(17 to 18, voter.add(1, 17 to 18))
        assertEquals(17 to 18, voter.add(1, null))
    }

    /** 画面变了：之前的票全部作废。 */
    @Test
    fun sceneChangeClearsTheVotes() {
        val voter = HandPositionVoter()
        voter.add(1, 16 to 17)
        voter.add(1, 16 to 17)
        assertEquals(15 to 15, voter.add(2, 15 to 15))
        assertNull(HandPositionVoter().also { it.add(1, 16 to 17) }.also { it.reset() }.add(1, null))
    }
}
