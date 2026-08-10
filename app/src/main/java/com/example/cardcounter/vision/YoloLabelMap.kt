package com.example.cardcounter.vision

import com.example.cardcounter.domain.Rank

/** 原模型28类：红/黑A-K归并为13个Rank，D/X为大小王。 */
object YoloLabelMap {
    val labels = listOf(
        "RA", "R2", "R3", "R4", "R5", "R6", "R7", "R8", "R9", "R10", "RJ", "RQ", "RK",
        "BA", "B2", "B3", "B4", "B5", "B6", "B7", "B8", "B9", "B10", "BJ", "BQ", "BK",
        "D", "X"
    )

    fun label(classId: Int): String = labels.getOrElse(classId) {
        error("未知YOLO类别: $classId")
    }

    fun rank(classId: Int): Rank = when (val raw = label(classId)) {
        "D" -> Rank.BIG_JOKER
        "X" -> Rank.SMALL_JOKER
        else -> when (raw.drop(1)) {
            "2" -> Rank.TWO
            "A" -> Rank.ACE
            "K" -> Rank.KING
            "Q" -> Rank.QUEEN
            "J" -> Rank.JACK
            "10" -> Rank.TEN
            "9" -> Rank.NINE
            "8" -> Rank.EIGHT
            "7" -> Rank.SEVEN
            "6" -> Rank.SIX
            "5" -> Rank.FIVE
            "4" -> Rank.FOUR
            "3" -> Rank.THREE
            else -> error("未知YOLO标签: $raw")
        }
    }
}
