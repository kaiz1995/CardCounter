package com.example.cardcounter.eval

import com.example.cardcounter.capture.PlaySource
import com.example.cardcounter.capture.PlayZone
import com.example.cardcounter.domain.Rank

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
     * 逐区对齐，分两轮。距离 = 入账时间到真值核心区间 [stable_ms, clear_ms] 的距离（区间内为 0）。
     * 第一轮：落在某手核心区间内的入账只认这些手，有未配对的 → 命中，都已配对 → 重复扣；
     * 落在上一手核心区间里的第二次入账因此不会被下一手的放宽窗吸收成命中。
     * 第二轮：落在两手之间空隙或放宽窗里的入账，只在窗内还没配对的手里按距离从近到远配对，
     * 空隙里的入账不会抢走核心区间内已有入账的手；窗内的手都已配对 → 重复扣（记到最近的手）；
     * 时间窗内没有真值 → 多记。
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

    private data class GapEdge(val distance: Long, val record: Int, val truth: TruthPlay)

    /** 入账时间到真值核心区间 [stable_ms, clear_ms] 的距离，区间内为 0。 */
    fun coreDistance(truth: TruthPlay, timestampMs: Long): Long = when {
        timestampMs < truth.stableMs -> truth.stableMs - timestampMs
        timestampMs > truth.clearMs -> timestampMs - truth.clearMs
        else -> 0L
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
        // 下标而不是对象本身：两条入账可能完全相同（同时间同牌），按值比较会混在一起。
        val gap = mutableListOf<Int>()
        for ((index, record) in accepted.withIndex()) {
            val core = truth.filter { coreDistance(it, record.timestampMs) == 0L }
            if (core.isEmpty()) {
                gap += index
                continue
            }
            val open = core.firstOrNull { it !in matched }
            if (open != null) {
                matched += open
                hits += MatchedPair(open, record)
            } else {
                duplicates += DuplicateRecord(record, core.first())
            }
        }
        val edges = gap.flatMap { index ->
            val timestampMs = accepted[index].timestampMs
            truth.filter { it !in matched && windows.contains(it, timestampMs) }
                .map { GapEdge(coreDistance(it, timestampMs), index, it) }
        }.sortedWith(
            compareBy<GapEdge>({ it.distance }, { accepted[it.record].timestampMs }, { it.truth.stableMs }, { it.truth.eventSeq })
        )
        val pairedGap = mutableSetOf<Int>()
        for (edge in edges) {
            if (edge.record in pairedGap || edge.truth in matched) continue
            matched += edge.truth
            pairedGap += edge.record
            hits += MatchedPair(edge.truth, accepted[edge.record])
        }
        for (index in gap) {
            if (index in pairedGap) continue
            val record = accepted[index]
            val inWindow = truth.filter { windows.contains(it, record.timestampMs) }
            if (inWindow.isEmpty()) {
                extras += record
            } else {
                val nearest = inWindow.sortedWith(
                    compareBy<TruthPlay>({ coreDistance(it, record.timestampMs) }, { it.stableMs }, { it.eventSeq })
                ).first()
                duplicates += DuplicateRecord(record, nearest)
            }
        }
        return ZoneMatchResult(
            zone = zone,
            truthHands = truth.size,
            hits = hits.sortedBy { it.record.timestampMs },
            missed = truth.filter { it !in matched },
            extras = extras,
            duplicates = duplicates.sortedBy { it.record.timestampMs },
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
