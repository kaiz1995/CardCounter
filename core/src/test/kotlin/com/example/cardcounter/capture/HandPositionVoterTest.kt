package com.example.cardcounter.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandPositionVoterTest {
    /** 同一画面里偶尔一帧数错，取多数，不跟着最新一帧走。 */
    @Test
    fun usesTheMostFrequentCountNotTheLatestFrame() {
        val voter = HandPositionVoter()
        assertEquals(33, voter.add(1, 33))
        assertEquals(33, voter.add(1, 33))
        assertEquals(33, voter.add(1, 34))
        assertEquals(33, voter.add(1, 32))
    }

    @Test
    fun tieGoesToTheMostRecentCount() {
        val voter = HandPositionVoter()
        voter.add(1, 33)
        assertEquals(34, voter.add(1, 34))
        assertEquals(33, voter.add(1, 33))
    }

    @Test
    fun framesWithoutACountDoNotVote() {
        val voter = HandPositionVoter()
        assertNull(voter.add(1, null))
        assertEquals(35, voter.add(1, 35))
        assertEquals(35, voter.add(1, null))
    }

    /** 画面变了：之前的票全部作废。 */
    @Test
    fun sceneChangeClearsTheVotes() {
        val voter = HandPositionVoter()
        voter.add(1, 33)
        voter.add(1, 33)
        assertEquals(30, voter.add(2, 30))
        assertNull(HandPositionVoter().also { it.add(1, 33) }.also { it.reset() }.add(1, null))
    }
}
