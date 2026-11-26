package com.example.cardcounter.capture

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.Image
import com.example.cardcounter.R
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts

/** 固定游戏皮肤的 Rank 角标模板；只输出候选，不直接写入会话。 */
class HandRankMatcher internal constructor(
    private val normal: List<Template>,
    private val jokers: List<Template>
) {
    internal data class Template(
        val rank: Rank,
        val width: Int,
        val height: Int,
        val ink: BooleanArray,
        val inkCount: Int,
        val minScore: Double? = null
    )
    data class InkFrame(val pixels: BooleanArray, val prefix: IntArray)
    internal data class Match(val x: Int, val rank: Rank, val score: Double)

    constructor(context: Context) : this(normalTemplates(context), jokerTemplates(context))

    fun snapshot(image: Image): BooleanArray = canonicalInk(image)

    fun prepare(pixels: BooleanArray): InkFrame {
        val prefix = IntArray((CANONICAL_WIDTH + 1) * (CANONICAL_HEIGHT + 1))
        for (y in 1..CANONICAL_HEIGHT) for (x in 1..CANONICAL_WIDTH) {
            prefix[y * (CANONICAL_WIDTH + 1) + x] =
                prefix[(y - 1) * (CANONICAL_WIDTH + 1) + x] +
                    prefix[y * (CANONICAL_WIDTH + 1) + x - 1] -
                    prefix[(y - 1) * (CANONICAL_WIDTH + 1) + x - 1] +
                    if (pixels[(y - 1) * CANONICAL_WIDTH + x - 1]) 1 else 0
        }
        return InkFrame(pixels, prefix)
    }

    fun detect(ink: InkFrame): RankCounts {
        val counts = Rank.entries.associateWith { 0 }.toMutableMap()
        val topMatches = findAcrossRows(ink, TOP_RANK_YS, normal, 180, 1_050, NORMAL_THRESHOLD, 40)
        val bottomMatches = findAcrossRows(
            ink, BOTTOM_RANK_YS, normal, 180, 1_050, NORMAL_THRESHOLD, 40
        )
        val jokerMatches = findAcrossRows(
            ink, JOKER_RANK_YS, jokers, 180, 400, JOKER_THRESHOLD, 8, JOKER_MATCH_GAP
        )
        (mergeNormalAndJokerMatches(topMatches, jokerMatches, DEFAULT_MATCH_GAP) + bottomMatches)
            .forEach { counts[it.rank] = counts.getValue(it.rank) + 1 }
        return RankCounts.of(counts)
    }

    private fun find(
        ink: InkFrame,
        y: Int,
        templates: List<Template>,
        minX: Int,
        maxX: Int,
        threshold: Double,
        maxCount: Int,
        minGap: Int = DEFAULT_MATCH_GAP
    ): List<Match> {
        val peaks = mutableListOf<Match>()
        for (x in minX..maxX) {
            val scored = templates.mapNotNull { template ->
                val minScore = template.minScore ?: threshold
                val sceneInk = inkCount(ink, x, y, template.width, template.height)
                if (!canReachIoUThreshold(sceneInk, template.inkCount, minScore)) null else {
                    val score = score(ink, x, y, template)
                    (template to score).takeIf { score >= minScore }
                }
            }
            val best = scored.maxByOrNull { it.second } ?: continue
            peaks += Match(x, best.first.rank, best.second)
        }
        return selectDistinctMatches(peaks, maxCount, minGap)
    }

    private fun findAcrossRows(
        ink: InkFrame,
        ys: IntArray,
        templates: List<Template>,
        minX: Int,
        maxX: Int,
        threshold: Double,
        maxCount: Int,
        minGap: Int = DEFAULT_MATCH_GAP
    ): List<Match> = selectDistinctMatches(
        ys.flatMap { y -> find(ink, y, templates, minX, maxX, threshold, maxCount, minGap) },
        maxCount,
        minGap
    )

    private fun score(ink: InkFrame, x: Int, y: Int, template: Template): Double {
        var intersection = 0
        var union = 0
        for (index in template.ink.indices) {
            val tx = index % template.width
            val ty = index / template.width
            val pixelInk = ink.pixels[(y + ty) * CANONICAL_WIDTH + x + tx]
            val templateOn = template.ink[index]
            if (pixelInk || templateOn) union++
            if (pixelInk && templateOn) intersection++
        }
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    private fun canonicalInk(image: Image): BooleanArray {
        val result = BooleanArray(CANONICAL_WIDTH * CANONICAL_HEIGHT)
        fillInk(image, result, 180, 1_079, 380, 480)
        return result
    }

    private fun inkCount(ink: InkFrame, left: Int, top: Int, width: Int, height: Int): Int {
        val stride = CANONICAL_WIDTH + 1
        val right = left + width
        val bottom = top + height
        return ink.prefix[bottom * stride + right] - ink.prefix[top * stride + right] -
            ink.prefix[bottom * stride + left] + ink.prefix[top * stride + left]
    }

    private fun fillInk(
        image: Image,
        target: BooleanArray,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int
    ) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        for (y in top.coerceAtLeast(0)..bottom.coerceAtMost(CANONICAL_HEIGHT - 1)) {
            for (x in left.coerceAtLeast(0)..right.coerceAtMost(CANONICAL_WIDTH - 1)) {
                val sourceX = x * image.width / CANONICAL_WIDTH
                val sourceY = y * image.height / CANONICAL_HEIGHT
                val offset = sourceY * plane.rowStride + sourceX * plane.pixelStride
                val red = buffer.get(offset).toInt() and 0xff
                val green = buffer.get(offset + 1).toInt() and 0xff
                val blue = buffer.get(offset + 2).toInt() and 0xff
                target[y * CANONICAL_WIDTH + x] = (red * 30 + green * 59 + blue * 11) / 100 < INK_LIMIT
            }
        }
    }

    companion object {
        private fun normalTemplates(context: Context): List<Template> = listOf(
            template(context, Rank.TWO, R.drawable.rank_two), template(context, Rank.ACE, R.drawable.rank_ace),
            template(context, Rank.KING, R.drawable.rank_king), template(context, Rank.QUEEN, R.drawable.rank_queen),
            template(context, Rank.JACK, R.drawable.rank_jack), template(context, Rank.TEN, R.drawable.rank_ten),
            template(context, Rank.TEN, R.drawable.rank_ten_black),
            template(context, Rank.NINE, R.drawable.rank_nine), template(context, Rank.EIGHT, R.drawable.rank_eight),
            template(context, Rank.SEVEN, R.drawable.rank_seven), template(context, Rank.SIX, R.drawable.rank_six),
            template(context, Rank.FIVE, R.drawable.rank_five), template(context, Rank.FIVE, R.drawable.rank_five_alt),
            template(context, Rank.FOUR, R.drawable.rank_four),
            template(context, Rank.THREE, R.drawable.rank_three)
        )

        private fun jokerTemplates(context: Context): List<Template> = listOf(
            template(context, Rank.BIG_JOKER, R.drawable.rank_big),
            template(context, Rank.BIG_JOKER, R.drawable.rank_big_alt),
            template(context, Rank.SMALL_JOKER, R.drawable.rank_small),
            template(context, Rank.SMALL_JOKER, R.drawable.rank_small_alt),
            template(context, Rank.SMALL_JOKER, R.drawable.rank_small_current)
        )

        private fun template(
            context: Context,
            rank: Rank,
            id: Int,
            minScore: Double? = null
        ): Template {
            val bitmap = requireNotNull(
                BitmapFactory.decodeResource(
                    context.resources,
                    id,
                    BitmapFactory.Options().apply { inScaled = false }
                )
            )
            val ink = BooleanArray(bitmap.width * bitmap.height) { index ->
                val color = bitmap.getPixel(index % bitmap.width, index / bitmap.width)
                // 和场景截图二值化（fillInk）同一个灰度公式，模板和场景才在同一把尺子上比。
                (Color.red(color) * 30 + Color.green(color) * 59 + Color.blue(color) * 11) / 100 < INK_LIMIT
            }
            return Template(rank, bitmap.width, bitmap.height, ink, ink.count { it }, minScore)
        }

        private const val CANONICAL_WIDTH = 1_280
        private const val CANONICAL_HEIGHT = 578
        private const val INK_LIMIT = 180
        private const val NORMAL_THRESHOLD = 0.55
        private const val JOKER_THRESHOLD = 0.72
        private const val DEFAULT_MATCH_GAP = 28
        private const val JOKER_MATCH_GAP = 20
        private val TOP_RANK_YS = intArrayOf(380, 385, 390, 395)
        private val JOKER_RANK_YS = intArrayOf(393, 395)
        private val BOTTOM_RANK_YS = intArrayOf(435, 437, 440, 445, 450, 455)
    }

}

internal fun canReachIoUThreshold(sceneInk: Int, templatePixels: Int, threshold: Double): Boolean {
    if (sceneInk == 0 || templatePixels == 0) return false
    return minOf(sceneInk, templatePixels).toDouble() / maxOf(sceneInk, templatePixels) >= threshold
}

internal fun selectDistinctMatches(
    matches: List<HandRankMatcher.Match>,
    maxCount: Int,
    minGap: Int = 28
): List<HandRankMatcher.Match> = buildList<HandRankMatcher.Match> {
    matches.sortedByDescending { it.score }.forEach { candidate ->
        if (size < maxCount && none { kotlin.math.abs(it.x - candidate.x) <= minGap }) add(candidate)
    }
}.sortedBy { it.x }

/**
 * 上排普通牌和大小王合并：普通牌和大小王之间按分数高的先留、相距不超过 [minGap] 的去重；
 * 大小王之间不再互相去重（它们已按 JOKER_MATCH_GAP 选过，相邻两张王间距可以小于 [minGap]）。
 * 最多留 [maxCount] 个。
 */
internal fun mergeNormalAndJokerMatches(
    normal: List<HandRankMatcher.Match>,
    jokers: List<HandRankMatcher.Match>,
    minGap: Int = 28,
    maxCount: Int = 40
): List<HandRankMatcher.Match> {
    val kept = ArrayList<HandRankMatcher.Match>()
    val keptNormal = ArrayList<HandRankMatcher.Match>()
    val ordered = (normal.map { it to false } + jokers.map { it to true }).sortedByDescending { it.first.score }
    for ((candidate, isJoker) in ordered) {
        if (kept.size >= maxCount) break
        val clash = if (isJoker) keptNormal else kept
        if (clash.any { kotlin.math.abs(it.x - candidate.x) <= minGap }) continue
        kept += candidate
        if (!isJoker) keptNormal += candidate
    }
    return kept.sortedBy { it.x }
}
