package com.example.cardcounter.domain

/** 所有牌数都按 Rank 完整归一化，避免缺字段产生隐式状态。 */
class RankCounts private constructor(private val values: Map<Rank, Int>) {
    operator fun get(rank: Rank): Int = values.getValue(rank)

    fun total(): Int = values.values.sum()

    fun asMap(): Map<Rank, Int> = values

    fun plus(other: RankCounts): RankCounts = of(
        Rank.entries.associateWith { this[it] + other[it] }
    )

    fun minus(other: RankCounts): RankCounts = of(
        Rank.entries.associateWith { this[it] - other[it] }
    )

    fun fitsWithin(limit: RankCounts): Boolean =
        Rank.entries.all { this[it] <= limit[it] }

    fun clampTo(limit: RankCounts): RankCounts = of(
        Rank.entries.associateWith { rank -> minOf(this[rank], limit[rank].coerceAtLeast(0)) }
    )

    override fun equals(other: Any?): Boolean =
        other is RankCounts && values == other.values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = values.toString()

    companion object {
        fun zero(): RankCounts = of(emptyMap())

        fun of(source: Map<Rank, Int>): RankCounts {
            val normalized = Rank.entries.associateWith { source[it] ?: 0 }
            require(normalized.values.all { it >= 0 }) { "牌数不能为负数" }
            return RankCounts(normalized)
        }
    }
}
