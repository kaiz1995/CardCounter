package com.example.cardcounter.domain

/** 观测到的 Rank 向量完整度；只有 COMPLETE 才允许进入完整 Rank 账本。 */
enum class RankCompleteness {
    /** Rank 向量完整可信，且必须与独立数量账一致。 */
    COMPLETE,

    /** 只识别到部分牌面，Rank 向量仅供参考。 */
    PARTIAL,

    /** 完全无法识别牌面，只有独立数量可信。 */
    UNKNOWN
}
