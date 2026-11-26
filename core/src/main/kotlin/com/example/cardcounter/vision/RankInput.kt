package com.example.cardcounter.vision

/** 把 320×320 的 ARGB 像素转成模型输入：RGB 顺序、值域 0..1、NCHW 展平（3×320×320）。 */
fun argbToRankTensor(pixels: IntArray): FloatArray {
    val size = RankModelContract.INPUT_SIZE
    val plane = size * size
    require(pixels.size == plane) { "输入应为 ${size}x$size 共 $plane 个像素，实际 ${pixels.size}" }
    val tensor = FloatArray(3 * plane)
    for (index in 0 until plane) {
        val color = pixels[index]
        tensor[index] = ((color shr 16) and 0xff) / 255f
        tensor[plane + index] = ((color shr 8) and 0xff) / 255f
        tensor[2 * plane + index] = (color and 0xff) / 255f
    }
    return tensor
}
