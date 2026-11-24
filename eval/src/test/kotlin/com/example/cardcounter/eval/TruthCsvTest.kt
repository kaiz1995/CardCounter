package com.example.cardcounter.eval

import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.domain.Rank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TruthCsvTest {
    private val header = TruthCsv.HEADER

    @Test
    fun parsesRowsAndQuotedNotes() {
        val text = """
            $header
            g000001-1,1,TOP,0,120,900,2,TWO:2,NORMAL,CONFIRMED,
            g000001-1,2,LEFT_TOP,900,950,1500,1,KING:1,OCCLUDED,FIXED,"人工复核, 有遮挡"
        """.trimIndent()

        val plays = TruthCsv.parse(text, expectedGameId = "g000001-1")

        assertEquals(2, plays.size)
        assertEquals(PlayZone.TOP, plays[0].zone)
        assertEquals(120L, plays[0].stableMs)
        assertEquals(2, plays[0].quantity)
        assertEquals(2, plays[0].cards[Rank.TWO])
        assertTrue(plays[0].hasRanks)
        assertEquals("人工复核, 有遮挡", plays[1].notes)
        assertEquals(3, plays[1].lineNumber)
    }

    @Test
    fun emptyCardsMeansRankUnknown() {
        val text = """
            $header
            g000001-1,1,SELF,0,10,20,3,,NORMAL,AUTO,
        """.trimIndent()

        val play = TruthCsv.parse(text).single()

        assertFalse(play.hasRanks)
        assertEquals(0, play.cards.total())
    }

    @Test
    fun reportsLineAndReasonForBadZoneAndQuantityMismatch() {
        val text = """
            $header
            g000001-1,1,MIDDLE,0,120,900,2,TWO:2,NORMAL,AUTO,
            g000001-1,2,TOP,900,950,1500,3,KING:1,NORMAL,AUTO,
        """.trimIndent()

        val error = runCatching { TruthCsv.parse(text) }.exceptionOrNull()

        assertTrue(error is DataFormatException)
        val message = error!!.message.orEmpty()
        assertTrue(message.contains("第 2 行"))
        assertTrue(message.contains("zone"))
        assertTrue(message.contains("第 3 行"))
        assertTrue(message.contains("quantity"))
    }

    @Test
    fun rejectsHeaderMismatch() {
        val error = runCatching {
            TruthCsv.parse("game_id,zone\nx,y")
        }.exceptionOrNull()

        assertTrue(error is DataFormatException)
        assertTrue(error!!.message.orEmpty().contains("表头不符"))
    }

    @Test
    fun rejectsCardsOutOfRankOrder() {
        val text = """
            $header
            g000001-1,1,TOP,0,120,900,2,KING:1;TWO:1,NORMAL,AUTO,
        """.trimIndent()

        val error = runCatching { TruthCsv.parse(text) }.exceptionOrNull()

        assertTrue(error is DataFormatException)
        assertTrue(error!!.message.orEmpty().contains("Rank 枚举顺序"))
    }

    @Test
    fun rejectsGameIdMismatch() {
        val text = """
            $header
            g000002-1,1,TOP,0,120,900,2,TWO:2,NORMAL,AUTO,
        """.trimIndent()

        val error = runCatching { TruthCsv.parse(text, expectedGameId = "g000001-1") }.exceptionOrNull()

        assertTrue(error is DataFormatException)
        assertTrue(error!!.message.orEmpty().contains("不一致"))
    }
}
