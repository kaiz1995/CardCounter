package com.example.cardcounter.capture

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.Image
import com.example.cardcounter.R
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts

/** 固定游戏皮肤的 Rank 角标模板；只输出候选，不直接写入会话。 */
class HandRankMatcher(context: Context) {
    private data class Template(
        val rank: Rank,
        val width: Int,
        val height: Int,
        val ink: BooleanArray,
        val inkCount: Int,
        val minScore: Double? = null
    )
    data class InkFrame(val pixels: BooleanArray, val prefix: IntArray)
    internal data class Match(val x: Int, val rank: Rank, val score: Double)

    private val normal = listOf(
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
    private val jokers = listOf(
        template(context, Rank.BIG_JOKER, R.drawable.rank_big),
        template(context, Rank.BIG_JOKER, R.drawable.rank_big_alt),
        template(context, Rank.SMALL_JOKER, R.drawable.rank_small),
        template(context, Rank.SMALL_JOKER, R.drawable.rank_small_alt),
        template(context, Rank.SMALL_JOKER, R.drawable.rank_small_current)
    )
    private val playNormal = normal.filterNot { it.rank == Rank.ACE } + listOf(
        template(context, Rank.ACE, R.drawable.rank_ace, ACE_PLAY_THRESHOLD),
        template(context, Rank.SIX, R.drawable.rank_six_play),
        template(context, Rank.FIVE, R.drawable.rank_five_play),
        template(context, Rank.FOUR, R.drawable.rank_four_play),
        template(context, Rank.TEN, R.drawable.rank_ten_play),
        template(context, Rank.QUEEN, R.drawable.rank_queen_play),
        template(context, Rank.EIGHT, R.drawable.rank_eight_play),
        template(context, Rank.SEVEN, R.drawable.rank_seven_play),
        template(context, Rank.SEVEN, R.drawable.rank_seven_play_alt)
    )
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
        (selectDistinctMatches(topMatches + jokerMatches, 40, DEFAULT_MATCH_GAP) + bottomMatches)
            .forEach { counts[it.rank] = counts.getValue(it.rank) + 1 }
        return RankCounts.of(counts)
    }

    /** 正式出牌区只取牌左上角的固定 Rank 行；空区返回零。 */
    fun detectPlay(ink: InkFrame, zone: PlayZone): RankCounts {
        val rankY = zone.rankY(CANONICAL_HEIGHT)
        val normalMatches = findBestRow(
            ink,
            (rankY - 4..rankY + 4).toList().toIntArray(),
            playNormal,
            zone.left(CANONICAL_WIDTH),
            zone.right(CANONICAL_WIDTH),
            NORMAL_THRESHOLD,
            MAX_PLAY_CARDS,
            PLAY_CARD_GAP
        )
        val jokerMatches = findBestRow(
            ink,
            (rankY - 4..rankY + 4).toList().toIntArray(),
            jokers,
            zone.left(CANONICAL_WIDTH),
            zone.right(CANONICAL_WIDTH),
            JOKER_THRESHOLD,
            MAX_PLAY_CARDS,
            PLAY_CARD_GAP
        )
        val counts = Rank.entries.associateWith { 0 }.toMutableMap()
        selectDistinctMatches(normalMatches + jokerMatches, MAX_PLAY_CARDS, PLAY_CARD_GAP)
            .forEach { counts[it.rank] = counts.getValue(it.rank) + 1 }
        return RankCounts.of(counts)
    }

    /** 单行的原始候选峰；不做去重，去重交给调用方决定作用范围。 */
    private fun findRaw(
        ink: InkFrame,
        y: Int,
        templates: List<Template>,
        minX: Int,
        maxX: Int,
        threshold: Double
    ): List<Match> {
        val peaks = mutableListOf<Match>()
        for (x in minX..maxX) {
            val scored = templates.mapNotNull { template ->
                val minScore = template.minScore ?: threshold
                val sceneInk = inkCount(ink, x, y, template.width, template.height)
                if (!canReachInkRatio(sceneInk, template.inkCount, minScore)) null else {
                    val score = score(ink, x, y, template)
                    (template to score).takeIf { score >= minScore }
                }
            }
            val best = scored.maxByOrNull { it.second } ?: continue
            peaks += Match(x, best.first.rank, best.second)
        }
        return peaks
    }

    /** 单行内去重后再选峰；`findBestRow` 依赖该语义比较各行的得分。 */
    private fun find(
        ink: InkFrame,
        y: Int,
        templates: List<Template>,
        minX: Int,
        maxX: Int,
        threshold: Double,
        maxCount: Int,
        minGap: Int = DEFAULT_MATCH_GAP
    ): List<Match> = selectDistinctMatches(
        findRaw(ink, y, templates, minX, maxX, threshold),
        maxCount,
        minGap
    )

    private fun findBestRow(
        ink: InkFrame,
        ys: IntArray,
        templates: List<Template>,
        minX: Int,
        maxX: Int,
        threshold: Double,
        maxCount: Int,
        minGap: Int = DEFAULT_MATCH_GAP
    ): List<Match> = ys.map { y -> find(ink, y, templates, minX, maxX, threshold, maxCount, minGap) }
        .maxWithOrNull(compareBy<List<Match>> { matches -> matches.sumOf { it.score } }.thenBy { it.size })
        .orEmpty()

    /**
     * 跨行只在合并后去重一次。
     * 若逐行先各自去重，同一张牌在两行的候选峰可能落在不同 x（偏差超过 minGap），
     * 合并时无法判定为同一张牌，导致该牌被重复计数。
     */
    private fun findAcrossRows(
        ink: InkFrame,
        ys: IntArray,
        templates: List<Template>,
        minX: Int,
        maxX: Int,
        threshold: Double,
        maxCount: Int,
        minGap: Int = DEFAULT_MATCH_GAP
    ): List<Match> = sweepAcrossRows(
        ys.flatMap { y -> findRaw(ink, y, templates, minX, maxX, threshold) },
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
            val templateInk = template.ink[index]
            if (pixelInk || templateInk) union++
            if (pixelInk && templateInk) intersection++
        }
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    private fun canonicalInk(image: Image): BooleanArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val result = BooleanArray(CANONICAL_WIDTH * CANONICAL_HEIGHT)
        fillInk(image, result, 180, 1_079, 380, 480)
        PlayZone.entries.forEach { zone ->
            val rankY = zone.rankY(CANONICAL_HEIGHT)
            fillInk(
                image,
                result,
                zone.left(CANONICAL_WIDTH),
                zone.right(CANONICAL_WIDTH) + maxTemplateWidth,
                rankY - 4,
                rankY + 4 + maxTemplateHeight
            )
        }
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
            Color.luminance(color) * 255 < INK_LIMIT
        }
        return Template(rank, bitmap.width, bitmap.height, ink, ink.count { it }, minScore)
    }

    companion object {
        private const val CANONICAL_WIDTH = 1_280
        private const val CANONICAL_HEIGHT = 578
        private const val INK_LIMIT = 180
        private const val NORMAL_THRESHOLD = 0.55
        private const val ACE_PLAY_THRESHOLD = 0.50
        private const val JOKER_THRESHOLD = 0.72
        private const val MAX_PLAY_CARDS = 12
        private const val DEFAULT_MATCH_GAP = 28
        private const val JOKER_MATCH_GAP = 20
        private const val PLAY_CARD_GAP = 10
        private val TOP_RANK_YS = intArrayOf(380, 385, 390, 395)
        private val JOKER_RANK_YS = intArrayOf(393, 395)
        private val BOTTOM_RANK_YS = intArrayOf(435, 437, 440, 445, 450, 455)
    }

    private val maxTemplateWidth = (normal + jokers).maxOf { it.width }
    private val maxTemplateHeight = (normal + jokers).maxOf { it.height }
}

/**
 * 墨量比值的**宽松上界剪枝**，不是严格 IoU。
 *
 * 只比较 scene 与 template 的墨量 min/max 比值是否可能达到 threshold：
 * 该比值是真实重叠率的上界（真 IoU 还需考虑空间位置对齐），因此比 IoU 更宽松，
 * 不会误杀本可命中的候选，仅用于在 `score()` 之前快速跳过明显不可能的模板。
 * 命名统一为 InkRatio 以免后续调参误当作 IoU 使用。
 */
internal fun canReachInkRatio(sceneInk: Int, templateInk: Int, threshold: Double): Boolean {
    if (sceneInk == 0 || templateInk == 0) return false
    return minOf(sceneInk, templateInk).toDouble() / maxOf(sceneInk, templateInk) >= threshold
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
 * 跨行候选的统一去重入口。
 * 必须先把所有行的候选峰合并，再一次性按 x 去重；
 * 逐行先各自去重会让同一张牌在两行选出不同 x，合并后无法识别为同一张。
 */
internal fun sweepAcrossRows(
    candidates: List<HandRankMatcher.Match>,
    maxCount: Int,
    minGap: Int = 28
): List<HandRankMatcher.Match> = selectDistinctMatches(candidates, maxCount, minGap)
