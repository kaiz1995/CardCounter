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
    private val guard = SessionGuard()

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

    /**
     * 输入是已经摆好的画布：出牌区是 320×320（letterbox），手牌是 870×870 手牌画布。
     * 不是 320 见方时用 [ScreenScale.resizeLinear] 缩，和训练端的双线性逐像素一致（不用系统的 createScaledBitmap）。
     */
    fun detect(bitmap: Bitmap, refineOutsideJoker: Boolean = false): List<YoloDetection> =
        guard.runIfOpen(emptyList<YoloDetection>()) { detectLocked(bitmap, refineOutsideJoker) }

    private fun detectLocked(bitmap: Bitmap, refineOutsideJoker: Boolean): List<YoloDetection> {
        val size = RankModelContract.INPUT_SIZE
        val source = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(source, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val pixels = if (bitmap.width == size && bitmap.height == size) source
            else ScreenScale.resizeLinear(source, bitmap.width, bitmap.height, size, size)
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
                val decoded = decodeRankOutput(raw)
                return if (refineOutsideJoker) refineNarrowLeftJoker(decoded, pixels) else decoded
            }
        }
    }

    /** 等进行中的这一次推理跑完再关会话；关了之后 [detect] 直接返回空列表。 */
    override fun close() {
        guard.close { session.close() }
    }

    companion object {
        const val MODEL_ASSET = "poker_rank15_yolo11n_320.onnx"
        private const val THREADS = 3
    }
}
