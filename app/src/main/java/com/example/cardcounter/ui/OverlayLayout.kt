package com.example.cardcounter.ui

import com.example.cardcounter.domain.OutsideSeat

object OverlayLayout {
    const val DESIGN_WIDTH = 1_280
    const val DESIGN_HEIGHT = 578

    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

    val seatPanels = linkedMapOf(
        OutsideSeat.TOP to Rect(439, 91, 350, 53),
        OutsideSeat.LEFT_TOP to Rect(49, 154, 344, 52),
        OutsideSeat.LEFT_BOTTOM to Rect(44, 322, 349, 52),
        OutsideSeat.RIGHT_TOP to Rect(868, 154, 344, 52),
        OutsideSeat.RIGHT_BOTTOM to Rect(866, 322, 346, 52)
    )
    val outsideRemaining = Rect(85, 495, 526, 76)
    val hideButton = Rect(10, 499, 68, 68)
    val resetButton = Rect(645, 500, 69, 68)
}
