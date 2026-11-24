package com.example.cardcounter.vision

import com.example.cardcounter.domain.Rank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RankDecoderTest {
    private fun emptyOutput() = FloatArray(RankModelContract.OUTPUT_SIZE)

    /** 在候选 index 处写入一个框和一个类别分数（其余类别分数为 0）。 */
    private fun FloatArray.put(
        index: Int, classId: Int, score: Float,
        cx: Float, cy: Float, width: Float, height: Float
    ) {
        val count = RankModelContract.CANDIDATES
        this[index] = cx
        this[count + index] = cy
        this[2 * count + index] = width
        this[3 * count + index] = height
        this[(4 + classId) * count + index] = score
    }

    @Test
    fun contractMatchesRankEnumAndOutputShape() {
        assertEquals(15, RankModelContract.CLASS_COUNT)
        assertEquals(Rank.entries.size, RankModelContract.CLASS_COUNT)
        assertEquals(19, RankModelContract.OUTPUT_ROWS)
        assertEquals(19 * 2100, RankModelContract.OUTPUT_SIZE)
        assertEquals(Rank.BIG_JOKER, Rank.entries[0])
        assertEquals(Rank.THREE, Rank.entries[14])
    }

    @Test
    fun emptyOutputGivesNoDetections() {
        assertTrue(decodeRankOutput(emptyOutput()).isEmpty())
    }

    @Test
    fun scoreBelowThresholdIsDroppedAndThresholdItselfIsKept() {
        val output = emptyOutput()
        output.put(0, 3, 0.24f, 50f, 50f, 20f, 20f)
        output.put(1, 4, 0.25f, 200f, 200f, 20f, 20f)
        val result = decodeRankOutput(output)
        assertEquals(1, result.size)
        assertEquals(Rank.KING, result[0].rank)
        assertEquals("KING", result[0].rawLabel)
        assertEquals(0.25f, result[0].confidence, 0f)
    }

    @Test
    fun classIdFollowsRankEntriesOrderAndBoxStaysInCanvasPixels() {
        val output = emptyOutput()
        Rank.entries.forEachIndexed { classId, _ ->
            output.put(classId, classId, 0.9f, 10f + classId * 20f, 100f, 8f, 12f)
        }
        val result = decodeRankOutput(output)
        assertEquals(15, result.size)
        result.forEach { detection ->
            val classId = detection.rank.ordinal
            assertEquals(10f + classId * 20f, detection.centerX, 0f)
            assertEquals(100f, detection.centerY, 0f)
            assertEquals(8f, detection.width, 0f)
            assertEquals(12f, detection.height, 0f)
        }
    }

    @Test
    fun overlappingBoxesOfDifferentClassesKeepOnlyTheHigherScore() {
        val output = emptyOutput()
        output.put(0, 3, 0.60f, 100f, 100f, 20f, 20f)
        output.put(1, 5, 0.80f, 101f, 100f, 20f, 20f)
        val result = decodeRankOutput(output)
        assertEquals(1, result.size)
        assertEquals(Rank.QUEEN, result[0].rank)
        assertEquals(0.80f, result[0].confidence, 0f)
    }

    @Test
    fun separateBoxesOfDifferentClassesAreBothKept() {
        val output = emptyOutput()
        output.put(0, 3, 0.60f, 50f, 100f, 20f, 20f)
        output.put(1, 5, 0.80f, 200f, 100f, 20f, 20f)
        val result = decodeRankOutput(output)
        assertEquals(listOf(Rank.QUEEN, Rank.ACE), result.map { it.rank })
    }

    @Test
    fun overlapBelowIouThresholdIsNotSuppressed() {
        // 宽 20 的两个框错开 12：交集 8×20=160，并集 640，IoU=0.25 <= 0.45。
        val output = emptyOutput()
        output.put(0, 3, 0.90f, 100f, 100f, 20f, 20f)
        output.put(1, 3, 0.80f, 112f, 100f, 20f, 20f)
        assertEquals(2, decodeRankOutput(output).size)
    }

    @Test
    fun overlapAboveIouThresholdIsSuppressed() {
        // 错开 4：交集 16×20=320，并集 480，IoU≈0.667 > 0.45。
        val output = emptyOutput()
        output.put(0, 3, 0.90f, 100f, 100f, 20f, 20f)
        output.put(1, 3, 0.80f, 104f, 100f, 20f, 20f)
        assertEquals(1, decodeRankOutput(output).size)
    }

    @Test
    fun equalScoresAreOrderedByCandidateIndexAndStayStable() {
        val output = emptyOutput()
        // 同分数、同位置、不同类别：只留候选编号小的。
        output.put(10, 6, 0.70f, 100f, 100f, 20f, 20f)
        output.put(11, 7, 0.70f, 100f, 100f, 20f, 20f)
        // 同分数、互不重叠：按候选编号顺序输出。
        output.put(20, 8, 0.70f, 250f, 250f, 10f, 10f)
        output.put(5, 9, 0.70f, 30f, 250f, 10f, 10f)
        val first = decodeRankOutput(output)
        val second = decodeRankOutput(output.copyOf())
        assertEquals(first, second)
        // 候选 5（EIGHT）、10（JACK）、20（NINE）同分数按编号输出；候选 11（TEN）与 10 重合被压掉。
        assertEquals(listOf(Rank.EIGHT, Rank.JACK, Rank.NINE), first.map { it.rank })
    }

    @Test
    fun candidateTakesTheHighestClassScore() {
        val output = emptyOutput()
        output.put(0, 2, 0.30f, 100f, 100f, 20f, 20f)
        output[(4 + 11) * RankModelContract.CANDIDATES] = 0.55f
        val result = decodeRankOutput(output)
        assertEquals(1, result.size)
        assertEquals(Rank.SIX, result[0].rank)
        assertEquals(0.55f, result[0].confidence, 0f)
    }

    @Test
    fun wrongOutputLengthIsRejected() {
        try {
            decodeRankOutput(FloatArray(10))
            fail("长度不对必须报错")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("${RankModelContract.OUTPUT_SIZE}"))
        }
    }

    @Test
    fun tensorIsRgbZeroToOneChannelFirst() {
        val plane = RankModelContract.INPUT_SIZE * RankModelContract.INPUT_SIZE
        val pixels = IntArray(plane) { 0xff000000.toInt() or (255 shl 16) or (128 shl 8) or 0 }
        val tensor = argbToRankTensor(pixels)
        assertEquals(3 * plane, tensor.size)
        assertEquals(1f, tensor[0], 0f)
        assertEquals(128f / 255f, tensor[plane], 0f)
        assertEquals(0f, tensor[2 * plane], 0f)
        assertEquals(1f, tensor[plane - 1], 0f)
    }
}
