package com.example.cardcounter.vision

import com.example.cardcounter.domain.Rank

data class YoloDetection(
    val rank: Rank,
    val rawLabel: String,
    val confidence: Float,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float
)
