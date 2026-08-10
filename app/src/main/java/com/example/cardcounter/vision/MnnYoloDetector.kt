package com.example.cardcounter.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.aijpq.pokerrecorder.service.MNNYoloDetector
import java.io.Closeable
import java.io.File

/** Mate 60 arm64后端；该机型OpenCL不可访问，默认使用MNN CPU。 */
class MnnYoloDetector(context: Context, useOpenCl: Boolean = false) : Closeable {
    private val detector = MNNYoloDetector()

    init {
        require("arm64-v8a" in Build.SUPPORTED_ABIS) { "MNN后端仅包含arm64-v8a" }
        val directory = File(context.filesDir, "yolo").apply { mkdirs() }
        val model = File(directory, MODEL_FILE)
        if (!model.isFile || model.length() != MODEL_SIZE) {
            context.assets.open(MODEL_ASSET).use { input ->
                model.outputStream().use(input::copyTo)
            }
        }
        val cache = File(directory, "mnn_cache.bin")
        check(detector.initialize(model.absolutePath, cache.absolutePath, useOpenCl, THREADS)) {
            detector.lastError()
        }
    }

    @Synchronized
    fun detect(
        bitmap: Bitmap,
        confidenceThreshold: Float = DEFAULT_CONFIDENCE,
        nmsThreshold: Float = DEFAULT_NMS
    ): List<YoloDetection> {
        val prepared = if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) bitmap
            else Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        return try {
            detector.detect(prepared, confidenceThreshold, nmsThreshold).map { result ->
                val classId = result.classId
                YoloDetection(
                    YoloLabelMap.rank(classId),
                    YoloLabelMap.label(classId),
                    result.confidence,
                    result.x,
                    result.y,
                    result.width,
                    result.height
                )
            }
        } finally {
            if (prepared !== bitmap) prepared.recycle()
        }
    }

    override fun close() {
        detector.close()
    }

    private companion object {
        const val MODEL_ASSET = "poker_yolo11_320.mnn"
        const val MODEL_FILE = "poker_yolo11_320.mnn"
        const val MODEL_SIZE = 10_434_188L
        const val THREADS = 3
        const val INPUT_SIZE = 320
        const val DEFAULT_CONFIDENCE = 0.25f
        const val DEFAULT_NMS = 0.45f
    }
}
