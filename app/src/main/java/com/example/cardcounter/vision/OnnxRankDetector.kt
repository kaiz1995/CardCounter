package com.example.cardcounter.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * 15 点数新模型的手机端检测器（官方 ONNX Runtime Android，CPU）。
 * 预处理和解码与电脑端评测一致：输入 RGB、0..1、NCHW；解码与不分类别 NMS 在 :core 的 decodeRankOutput。
 */
class OnnxRankDetector(context: Context) : Closeable {
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val options = OrtSession.SessionOptions()
        try {
            options.setIntraOpNumThreads(THREADS)
            session = environment.createSession(modelBytes, options)
        } finally {
            options.close()
        }
        inputName = session.inputNames.first()
    }

    /** 输入是已经摆好的 320×320 画布（出牌区 letterbox、手牌 870×870 画布）；尺寸不对时直接缩放。 */
    @Synchronized
    fun detect(bitmap: Bitmap): List<YoloDetection> {
        val size = RankModelContract.INPUT_SIZE
        val prepared = if (bitmap.width == size && bitmap.height == size) bitmap
            else Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        try {
            prepared.getPixels(pixels, 0, size, 0, 0, size, size)
        } finally {
            if (prepared !== bitmap) prepared.recycle()
        }
        val tensor = argbToRankTensor(pixels)
        val shape = longArrayOf(1, 3, size.toLong(), size.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(tensor), shape).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val output = result[0] as OnnxTensor
                val outputShape = output.info.shape
                check(
                    outputShape.contentEquals(
                        longArrayOf(1, RankModelContract.OUTPUT_ROWS.toLong(), RankModelContract.CANDIDATES.toLong())
                    )
                ) { "不支持的模型输出: ${outputShape.contentToString()}" }
                val buffer = output.floatBuffer
                val raw = FloatArray(buffer.remaining())
                buffer.get(raw)
                return decodeRankOutput(raw)
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val MODEL_ASSET = "poker_rank15_yolo11n_320.onnx"
        private const val THREADS = 3
    }
}
