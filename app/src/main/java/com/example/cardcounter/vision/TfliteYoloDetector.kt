package com.example.cardcounter.vision

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** 原YOLO11 packed-320模型的CPU正确性基线；暂不直接驱动扣牌事件。 */
class TfliteYoloDetector(context: Context) : Closeable {
    private val interpreter: Interpreter
    private val inputWidth: Int
    private val inputHeight: Int
    private val detectionSize: Int
    private val detectionCount: Int
    private val input: ByteBuffer
    private val output: ByteBuffer

    init {
        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(modelBytes.size)
            .order(ByteOrder.nativeOrder())
            .put(modelBytes)
        model.rewind()
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(DEFAULT_THREADS))

        val inputShape = interpreter.getInputTensor(0).shape()
        require(inputShape.size == 4 && inputShape[0] == 1 && inputShape[3] == 3) {
            "不支持的YOLO输入: ${inputShape.contentToString()}"
        }
        inputHeight = inputShape[1]
        inputWidth = inputShape[2]

        val outputShape = interpreter.getOutputTensor(0).shape()
        require(outputShape.size == 3 && outputShape[0] == 1) {
            "不支持的YOLO输出: ${outputShape.contentToString()}"
        }
        detectionSize = outputShape[1]
        detectionCount = outputShape[2]
        require(detectionSize == 4 + YoloLabelMap.labels.size) {
            "YOLO类别数不匹配: ${outputShape.contentToString()}"
        }

        input = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        output = ByteBuffer.allocateDirect(detectionSize * detectionCount * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
    }

    @Synchronized
    fun detect(
        bitmap: Bitmap,
        confidenceThreshold: Float = DEFAULT_CONFIDENCE,
        nmsThreshold: Float = DEFAULT_NMS
    ): List<YoloDetection> {
        preprocess(bitmap)
        output.rewind()
        interpreter.run(input, output)
        output.rewind()

        val candidates = buildList {
            repeat(detectionCount) { index ->
                val centerX = output.getFloat(index * Float.SIZE_BYTES)
                val centerY = output.getFloat((detectionCount + index) * Float.SIZE_BYTES)
                val width = output.getFloat((detectionCount * 2 + index) * Float.SIZE_BYTES)
                val height = output.getFloat((detectionCount * 3 + index) * Float.SIZE_BYTES)
                var classId = 0
                var confidence = 0f
                repeat(detectionSize - 4) { candidateClass ->
                    val score = output.getFloat(
                        ((candidateClass + 4) * detectionCount + index) * Float.SIZE_BYTES
                    )
                    if (score > confidence) {
                        confidence = score
                        classId = candidateClass
                    }
                }
                if (confidence > confidenceThreshold) {
                    add(
                        YoloDetection(
                            YoloLabelMap.rank(classId),
                            YoloLabelMap.label(classId),
                            confidence,
                            centerX,
                            centerY,
                            width,
                            height
                        )
                    )
                }
            }
        }
        return nonMaxSuppression(candidates, nmsThreshold)
    }

    private fun preprocess(bitmap: Bitmap) {
        val prepared = if (bitmap.width == inputWidth && bitmap.height == inputHeight) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        }
        try {
            val pixels = IntArray(inputWidth * inputHeight)
            prepared.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
            input.rewind()
            pixels.forEach { color ->
                input.putFloat(((color shr 16) and 0xff) / 255f)
                input.putFloat(((color shr 8) and 0xff) / 255f)
                input.putFloat((color and 0xff) / 255f)
            }
            input.rewind()
        } finally {
            if (prepared !== bitmap) prepared.recycle()
        }
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        private const val MODEL_ASSET = "poker_yolo11_320.tflite"
        private const val DEFAULT_THREADS = 4
        private const val DEFAULT_CONFIDENCE = 0.25f
        private const val DEFAULT_NMS = 0.45f
    }
}

internal fun nonMaxSuppression(
    detections: List<YoloDetection>,
    threshold: Float
): List<YoloDetection> = buildList {
    detections.sortedByDescending { it.confidence }.forEach { candidate ->
        if (none { intersectionOverUnion(candidate, it) > threshold }) add(candidate)
    }
}

private fun intersectionOverUnion(first: YoloDetection, second: YoloDetection): Float {
    val firstLeft = first.centerX - first.width / 2f
    val firstTop = first.centerY - first.height / 2f
    val firstRight = first.centerX + first.width / 2f
    val firstBottom = first.centerY + first.height / 2f
    val secondLeft = second.centerX - second.width / 2f
    val secondTop = second.centerY - second.height / 2f
    val secondRight = second.centerX + second.width / 2f
    val secondBottom = second.centerY + second.height / 2f
    val width = min(firstRight, secondRight) - max(firstLeft, secondLeft)
    val height = min(firstBottom, secondBottom) - max(firstTop, secondTop)
    if (width <= 0f || height <= 0f) return 0f
    val intersection = width * height
    val union = first.width * first.height + second.width * second.height - intersection
    return if (union <= 0f) 0f else intersection / union
}
