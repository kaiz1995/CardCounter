package com.example.cardcounter.domain

/** 五个固定对手座位；名称与横屏识别区一一对应。 */
enum class OutsideSeat(val displayName: String) {
    TOP("顶家"),
    LEFT_TOP("左上"),
    LEFT_BOTTOM("左下"),
    RIGHT_TOP("右上"),
    RIGHT_BOTTOM("右下")
}
