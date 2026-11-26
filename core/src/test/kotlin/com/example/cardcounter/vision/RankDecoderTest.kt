package com.example.cardcounter.vision

import com.example.cardcounter.domain.Rank
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.imageio.ImageIO

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

    private fun blankArgb320(rgb: Int = 0xf0f0f0): IntArray =
        IntArray(RankModelContract.INPUT_SIZE * RankModelContract.INPUT_SIZE) { 0xff000000.toInt() or rgb }

    private fun paintRect(
        argb: IntArray,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        rgb: Int
    ) {
        val size = RankModelContract.INPUT_SIZE
        val color = 0xff000000.toInt() or rgb
        for (y in y0.coerceIn(0, size) until y1.coerceIn(0, size)) {
            for (x in x0.coerceIn(0, size) until x1.coerceIn(0, size)) {
                argb[y * size + x] = color
            }
        }
    }

    @Test
    fun narrowLeftRedStripMisclassifiedAsNormalRanksIsRefinedToBigJoker() {
        val argb = blankArgb320()
        // 最左框 cx=20, cy=180, w=14.8, h=29.2 (x=12..28, y=165..195)：顶部 0..25% 在右半侧画细竖笔“J”，中段画椭圆“O”。
        paintRect(argb, 22, 165, 25, 178, 0xD02020)
        paintRect(argb, 14, 180, 26, 189, 0xD02020)
        for (misRank in listOf(Rank.JACK, Rank.KING, Rank.ACE)) {
            val dets = listOf(
                YoloDetection(misRank, misRank.name, 0.84f, 20f, 180f, 14.8f, 29.2f),
                YoloDetection(misRank, misRank.name, 0.94f, 62f, 184f, 24.0f, 36.0f)
            )
            val refined = refineNarrowLeftJoker(dets, argb)
            assertEquals("红字竖条误认成 $misRank 应纠偏为 BIG_JOKER", listOf(Rank.BIG_JOKER, misRank), refined.map { it.rank })
        }
    }

    @Test
    fun narrowLeftBlackStripWithoutBottomRedBadgeIsRefinedToSmallJokerAndBottomBadgeBlocksIt() {
        val cleanSmallJoker = blankArgb320()
        // 最左框 cx=120, cy=180, w=13.6, h=31.1 (x=113..127, y=164..196)：右半侧画细竖笔“J”，中段画黑字。
        paintRect(cleanSmallJoker, 121, 164, 124, 178, 0x151515)
        paintRect(cleanSmallJoker, 116, 179, 125, 184, 0x151515)
        for (misRank in listOf(Rank.FIVE, Rank.TWO, Rank.THREE)) {
            val dets = listOf(
                YoloDetection(misRank, misRank.name, 0.56f, 120f, 180f, 13.6f, 31.1f),
                YoloDetection(misRank, misRank.name, 0.96f, 160f, 182f, 21.4f, 34.3f)
            )
            val refined = refineNarrowLeftJoker(dets, cleanSmallJoker)
            assertEquals("黑字竖条误认成 $misRank 应纠偏为 SMALL_JOKER", listOf(Rank.SMALL_JOKER, misRank), refined.map { it.rank })
        }

        // 若底部 35%（y=186..195）出现红圆标侵入，说明可能是红圆标压住普通黑牌左下角，不得误改成 SMALL_JOKER。
        val withRedBadge = cleanSmallJoker.copyOf()
        paintRect(withRedBadge, 114, 188, 127, 195, 0xD02020)
        val occluded = listOf(
            YoloDetection(Rank.EIGHT, "EIGHT", 0.78f, 120f, 180f, 13.6f, 31.1f),
            YoloDetection(Rank.EIGHT, "EIGHT", 0.96f, 160f, 182f, 21.4f, 34.3f)
        )
        assertEquals(listOf(Rank.EIGHT, Rank.EIGHT), refineNarrowLeftJoker(occluded, withRedBadge).map { it.rank })

        // 若顶部 15% 横向撑满左半区（普通数字如 10/8/J 顶横），不得误改成王。
        val wideTop = blankArgb320()
        paintRect(wideTop, 114, 164, 126, 171, 0x151515)
        assertEquals(listOf(Rank.TEN, Rank.TEN), refineNarrowLeftJoker(
            listOf(
                YoloDetection(Rank.TEN, "TEN", 0.40f, 120f, 180f, 13.6f, 31.1f),
                YoloDetection(Rank.TEN, "TEN", 0.95f, 160f, 182f, 21.4f, 34.3f)
            ),
            wideTop
        ).map { it.rank })
    }

    @Test
    fun normalAspectOrSingleCardOrAmbiguousColorIsNotModified() {
        val argb = blankArgb320()
        // 绘符合 J 形态的细竖笔墨迹，确保下面几个测试真正检验的是几何/张数门而非墨迹形态门。
        paintRect(argb, 22, 165, 25, 178, 0xD02020)
        paintRect(argb, 15, 179, 25, 185, 0xD02020)
        // 1) 普通牌正常高宽比（如 1.66 < 1.85）即使较窄也不改
        val normalAspect = listOf(
            YoloDetection(Rank.TWO, "TWO", 0.96f, 20f, 180f, 15.0f, 24.9f),
            YoloDetection(Rank.EIGHT, "EIGHT", 0.96f, 60f, 180f, 22.0f, 34.0f)
        )
        assertEquals(listOf(Rank.TWO, Rank.EIGHT), refineNarrowLeftJoker(normalAspect, argb).map { it.rank })

        // 2) 高宽比够高但宽度与右牌接近（未体现扇形左侧遮挡窄化，w/maxRight > 0.72）不改
        val similarWidth = listOf(
            YoloDetection(Rank.FIVE, "FIVE", 0.90f, 20f, 180f, 16.0f, 32.0f),
            YoloDetection(Rank.FIVE, "FIVE", 0.95f, 60f, 180f, 20.0f, 32.0f)
        )
        assertEquals(listOf(Rank.FIVE, Rank.FIVE), refineNarrowLeftJoker(similarWidth, argb).map { it.rank })

        // 3) 单张牌不改
        val single = listOf(YoloDetection(Rank.JACK, "JACK", 0.90f, 20f, 180f, 14.0f, 30.0f))
        assertEquals(listOf(Rank.JACK), refineNarrowLeftJoker(single, argb).map { it.rank })

        // 4) 框内无足够红/黑像素（空白或纯灰）不乱改
        val blankArgb = blankArgb320()
        val ambiguous = listOf(
            YoloDetection(Rank.JACK, "JACK", 0.80f, 20f, 180f, 14.0f, 30.0f),
            YoloDetection(Rank.JACK, "JACK", 0.95f, 60f, 180f, 24.0f, 36.0f)
        )
        assertEquals(listOf(Rank.JACK, Rank.JACK), refineNarrowLeftJoker(ambiguous, blankArgb).map { it.rank })
    }

    @Test
    fun realCapturedFramesNeverMisrefineOccludedNormalCardsAndStillFixJokers() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/joker_refine/cases.json")) {
            "缺少 /joker_refine/cases.json"
        }
        val cases = JSONArray(stream.use { it.readBytes().toString(Charsets.UTF_8) })
        val size = RankModelContract.INPUT_SIZE
        for (i in 0 until cases.length()) {
            val item = cases.getJSONObject(i)
            val id = item.getString("id")
            val imageName = item.getString("image")
            val expectedLeftRank = Rank.valueOf(item.getString("expectedLeftRank"))
            val imgStream = requireNotNull(javaClass.getResourceAsStream("/joker_refine/$imageName")) {
                "缺少 /joker_refine/$imageName"
            }
            val img = imgStream.use { ImageIO.read(it) }
            assertEquals("$id 宽", size, img.width)
            assertEquals("$id 高", size, img.height)
            val argb = IntArray(size * size) { idx -> img.getRGB(idx % size, idx / size) }
            val rawArray = item.getJSONArray("rawDetections")
            val dets = (0 until rawArray.length()).map { j ->
                val d = rawArray.getJSONObject(j)
                val rank = Rank.valueOf(d.getString("rank"))
                YoloDetection(
                    rank = rank,
                    rawLabel = rank.name,
                    confidence = d.getDouble("conf").toFloat(),
                    centerX = d.getDouble("cx").toFloat(),
                    centerY = d.getDouble("cy").toFloat(),
                    width = d.getDouble("w").toFloat(),
                    height = d.getDouble("h").toFloat()
                )
            }
            val refined = refineNarrowLeftJoker(dets, argb)
            val leftIndex = refined.indices.minBy { refined[it].centerX }
            assertEquals("$id 最左张结果不符", expectedLeftRank, refined[leftIndex].rank)
        }
    }
}
