package com.example.cardcounter.domain

/** 固定四副牌、198 张牌的 15 个点数。 */
enum class Rank(val label: String, val initialCount: Int) {
    BIG_JOKER("大", 4),
    SMALL_JOKER("小", 4),
    TWO("2", 16),
    ACE("A", 16),
    KING("K", 16),
    QUEEN("Q", 16),
    JACK("J", 16),
    TEN("10", 16),
    NINE("9", 16),
    EIGHT("8", 16),
    SEVEN("7", 16),
    SIX("6", 16),
    FIVE("5", 16),
    FOUR("4", 8),
    THREE("3", 6);

    companion object {
        val initialInventory: RankCounts = RankCounts.of(
            entries.associateWith { it.initialCount }
        )
    }
}
