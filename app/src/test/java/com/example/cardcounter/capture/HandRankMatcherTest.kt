package com.example.cardcounter.capture

import com.example.cardcounter.domain.Rank
import org.junit.Assert.assertEquals
import org.junit.Test

class HandRankMatcherTest {
    private val canvasWidth = 1_280
    private val canvasHeight = 578

    private fun solidTemplate(rank: Rank, size: Int = 20) =
        HandRankMatcher.Template(rank, size, size, BooleanArray(size * size) { true }, size * size)

    /** 在 1280x578 画布上画若干实心方块（左上角 x、y，边长 size）当作牌角标的墨迹。 */
    private fun frameWithBlocks(vararg blocks: Triple<Int, Int, Int>): HandRankMatcher.InkFrame {
        val pixels = BooleanArray(canvasWidth * canvasHeight)
        for ((left, top, size) in blocks) {
            for (y in top until top + size) for (x in left until left + size) pixels[y * canvasWidth + x] = true
        }
        return HandRankMatcher(emptyList(), listOf(solidTemplate(Rank.SMALL_JOKER))).prepare(pixels)
    }

    /** 回归：两张小王相距 25px（小于普通牌的 28px 去重间距，大于王自己的 20px），完整 detect 后仍是 2 张。 */
    @Test
    fun twoAdjacentSmallJokers25PixelsApartAreBothKept() {
        val matcher = HandRankMatcher(emptyList(), listOf(solidTemplate(Rank.SMALL_JOKER)))
        val frame = frameWithBlocks(Triple(300, 393, 20), Triple(325, 393, 20))
        val counts = matcher.detect(frame)
        assertEquals(2, counts[Rank.SMALL_JOKER])
        assertEquals(2, counts.total())
    }

    @Test
    fun singleSmallJokerStaysOne() {
        val matcher = HandRankMatcher(emptyList(), listOf(solidTemplate(Rank.SMALL_JOKER)))
        val counts = matcher.detect(frameWithBlocks(Triple(300, 393, 20)))
        assertEquals(1, counts[Rank.SMALL_JOKER])
        assertEquals(1, counts.total())
    }

    private fun match(x: Int, rank: Rank, score: Double) = HandRankMatcher.Match(x, rank, score)

    @Test
    fun jokersAreNotDedupedAgainstEachOther() {
        val merged = mergeNormalAndJokerMatches(
            normal = emptyList(),
            jokers = listOf(match(300, Rank.BIG_JOKER, 0.9), match(325, Rank.BIG_JOKER, 0.8))
        )
        assertEquals(listOf(300, 325), merged.map { it.x })
    }

    @Test
    fun jokerCloseToANormalCardIsDroppedWhenNormalScoresHigher() {
        val merged = mergeNormalAndJokerMatches(
            normal = listOf(match(300, Rank.ACE, 0.9)),
            jokers = listOf(match(315, Rank.SMALL_JOKER, 0.8))
        )
        assertEquals(listOf(Rank.ACE), merged.map { it.rank })
    }

    @Test
    fun normalCardCloseToAJokerIsDroppedWhenJokerScoresHigher() {
        val merged = mergeNormalAndJokerMatches(
            normal = listOf(match(300, Rank.ACE, 0.7)),
            jokers = listOf(match(315, Rank.SMALL_JOKER, 0.9))
        )
        assertEquals(listOf(Rank.SMALL_JOKER), merged.map { it.rank })
    }

    @Test
    fun normalCardsStillDedupeEachOtherByGap() {
        val merged = mergeNormalAndJokerMatches(
            normal = listOf(match(300, Rank.ACE, 0.9), match(320, Rank.ACE, 0.8), match(360, Rank.KING, 0.7)),
            jokers = emptyList()
        )
        assertEquals(listOf(300, 360), merged.map { it.x })
    }
}
