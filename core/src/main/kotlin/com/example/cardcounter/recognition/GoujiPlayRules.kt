package com.example.cardcounter.recognition

import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts

/** 多乐够级（四副牌 198 张）出牌合法性兜底校验。 */
object GoujiPlayRules : PlayRuleValidator {
    /** 可与主牌搭配的配牌点数集合（大王、小王、2）。 */
    val WILD_RANKS: Set<Rank> = setOf(
        Rank.BIG_JOKER,
        Rank.SMALL_JOKER,
        Rank.TWO
    )

    override fun check(cards: RankCounts): PlayRuleCheck {
        if (cards.total() == 0) {
            return PlayRuleCheck.Suspicious(
                code = "EMPTY",
                detail = "识别结果为空牌（0 张）"
            )
        }

        val overInventory = Rank.entries.filter { cards[it] > it.initialCount }
        if (overInventory.isNotEmpty()) {
            val summary = overInventory.joinToString("、") { rank ->
                "${rank.label} 有 ${cards[rank]} 张（上限 ${rank.initialCount} 张）"
            }
            return PlayRuleCheck.Suspicious(
                code = "OVER_INVENTORY",
                detail = "点数张数超过四副牌初始库存：$summary"
            )
        }

        val mainRanks = Rank.entries.filter { it !in WILD_RANKS && cards[it] > 0 }
        if (mainRanks.size >= 2) {
            val detail = if (mainRanks.size == 2) {
                "同一手出现 ${mainRanks[0].label} 和 ${mainRanks[1].label} 两种主牌"
            } else {
                val names = mainRanks.joinToString("、") { it.label }
                "同一手出现 $names 共 ${mainRanks.size} 种主牌"
            }
            return PlayRuleCheck.Suspicious(
                code = "MIXED_RANKS",
                detail = detail
            )
        }

        return PlayRuleCheck.Valid
    }
}
