package com.example.cardcounter.eval

import com.example.cardcounter.capture.PlaySource
import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.domain.Rank
import kotlin.math.abs

/** 对齐时间窗：真值 [stable - before, clear + after]。 */
data class MatchWindows(
    val beforeSlackMs: Long = 500,
    val afterSlackMs: Long = 500
) {
    init {
        require(beforeSlackMs >= 0 && afterSlackMs >= 0) { "时间窗放宽不能为负" }
    }

    fun contains(truth: TruthPlay, timestampMs: Long): Boolean =
        timestampMs >= truth.stableMs - beforeSlackMs && timestampMs <= truth.clearMs + afterSlackMs
}

data class MatchedPair(
    val truth: TruthPlay,
    val record: AcceptedPlay
) {
    val quantityExact: Boolean get() = record.cardCount == truth.quantity
    val rankExact: Boolean get() = truth.hasRanks && record.cards == truth.cards
}

/** 同一手真值被记了第二次。 */
data class DuplicateRecord(val record: AcceptedPlay, val truth: TruthPlay)

data class ZoneMatchResult(
    val zone: PlayZone,
    val truthHands: Int,
    val hits: List<MatchedPair>,
    val missed: List<TruthPlay>,
    val extras: List<AcceptedPlay>,
    val duplicates: List<DuplicateRecord>,
    val rankKnownTruth: Int,
    val confusion: Array<IntArray>
) {
    val quantityExact: Int get() = hits.count { it.quantityExact }
    val rankExact: Int get() = hits.count { it.rankExact }
}

data class MatchResult(
    val perZone: Map<PlayZone, ZoneMatchResult>,
    val windows: MatchWindows
) {
    val hits: List<MatchedPair> get() = perZone.values.flatMap { it.hits }
    val missed: List<TruthPlay> get() = perZone.values.flatMap { it.missed }
    val extras: List<AcceptedPlay> get() = perZone.values.flatMap { it.extras }
    val duplicates: List<DuplicateRecord> get() = perZone.values.flatMap { it.duplicates }
    val truthHands: Int get() = perZone.values.sumOf { it.truthHands }
    val rankKnownTruth: Int get() = perZone.values.sumOf { it.rankKnownTruth }
    val quantityExact: Int get() = perZone.values.sumOf { it.quantityExact }
    val rankExact: Int get() = perZone.values.sumOf { it.rankExact }

    /** 误扣：外面出牌被多记。自己的多记不影响剩余牌，单独区分。 */
    val extrasOutside: Int get() = extras.count { it.zone.source == PlaySource.OUTSIDE }

    val hitRate: Double get() = ratio(hits.size, truthHands)
    val quantityExactRate: Double get() = ratio(quantityExact, truthHands)
    val rankExactRate: Double get() = ratio(rankExact, rankKnownTruth)

    val confusion: Array<IntArray> get() = EventMatcher.buildConfusion(hits.filter { it.truth.hasRanks })

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 0.0 else numerator.toDouble() / denominator
}

object EventMatcher {
    /**
     * 逐区一对一贪心对齐：按入账时间顺序，优先匹配时间窗内未配对、且 stable_ms 最近的一手。
     * 时间窗内只剩已配对手 → 重复记；没有可用真值 → 多记。
     */
    fun match(
        truth: List<TruthPlay>,
        accepted: List<AcceptedPlay>,
        windows: MatchWindows = MatchWindows()
    ): MatchResult {
        val perZone = PlayZone.entries.associateWith { zone ->
            matchZone(
                zone,
                truth.filter { it.zone == zone }.sortedWith(compareBy({ it.stableMs }, { it.eventSeq })),
                accepted.filter { it.zone == zone }.sortedBy { it.timestampMs },
                windows
            )
        }
        return MatchResult(perZone, windows)
    }

    private fun matchZone(
        zone: PlayZone,
        truth: List<TruthPlay>,
        accepted: List<AcceptedPlay>,
        windows: MatchWindows
    ): ZoneMatchResult {
        val matched = mutableSetOf<TruthPlay>()
        val hits = mutableListOf<MatchedPair>()
        val duplicates = mutableListOf<DuplicateRecord>()
        val extras = mutableListOf<AcceptedPlay>()
        for (record in accepted) {
            val open = truth
                .filter { it !in matched && windows.contains(it, record.timestampMs) }
                .minWithOrNull(
                    compareBy<TruthPlay> { abs(it.stableMs - record.timestampMs) }.thenBy { it.eventSeq }
                )
            if (open != null) {
                matched += open
                hits += MatchedPair(open, record)
                continue
            }
            val already = truth
                .filter { it in matched && windows.contains(it, record.timestampMs) }
                .minWithOrNull(
                    compareBy<TruthPlay> { abs(it.stableMs - record.timestampMs) }.thenBy { it.eventSeq }
                )
            if (already != null) {
                duplicates += DuplicateRecord(record, already)
            } else {
                extras += record
            }
        }
        return ZoneMatchResult(
            zone = zone,
            truthHands = truth.size,
            hits = hits,
            missed = truth.filter { it !in matched },
            extras = extras,
            duplicates = duplicates,
            rankKnownTruth = truth.count { it.hasRanks },
            confusion = buildConfusion(hits.filter { it.truth.hasRanks })
        )
    }

    /**
     * 张级混淆矩阵：先按点数配对角，剩余张数再按 Rank 顺序贪心配对。
     * 只统计真值点数已知的命中手。
     */
    fun buildConfusion(pairs: List<MatchedPair>): Array<IntArray> {
        val size = Rank.entries.size
        val matrix = Array(size) { IntArray(size) }
        for (pair in pairs) {
            val truthLeft = IntArray(size) { pair.truth.cards[Rank.entries[it]] }
            val recordLeft = IntArray(size) { pair.record.cards[Rank.entries[it]] }
            for (index in 0 until size) {
                val diagonal = minOf(truthLeft[index], recordLeft[index])
                matrix[index][index] += diagonal
                truthLeft[index] -= diagonal
                recordLeft[index] -= diagonal
            }
            var truthRank = 0
            var recordRank = 0
            while (truthRank < size && recordRank < size) {
                if (truthLeft[truthRank] == 0) {
                    truthRank++
                    continue
                }
                if (recordLeft[recordRank] == 0) {
                    recordRank++
                    continue
                }
                val count = minOf(truthLeft[truthRank], recordLeft[recordRank])
                matrix[truthRank][recordRank] += count
                truthLeft[truthRank] -= count
                recordLeft[recordRank] -= count
            }
        }
        return matrix
    }
}
