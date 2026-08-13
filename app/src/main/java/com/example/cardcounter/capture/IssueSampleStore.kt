package com.example.cardcounter.capture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject

/** 只留存无法自动确认的公开牌 ROI，供后续人工标注；绝不保存整屏。 */
internal class IssueSampleStore(context: Context) : AutoCloseable {
    private val root = File(context.filesDir, "card_counter_issues")
    private val executor = Executors.newSingleThreadExecutor()

    fun save(
        bitmap: Bitmap,
        zone: PlayZone,
        reason: String,
        candidates: List<RankCountsCandidate>
    ) {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        executor.execute {
            runCatching {
                root.mkdirs()
                trim()
                val directory = File(root, "${System.currentTimeMillis()}_${zone.name}_${reason}")
                check(directory.mkdirs()) { "无法创建问题样本目录" }
                File(directory, "roi.png").outputStream().use { output ->
                    check(copy.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法写入问题 ROI" }
                }
                val meta = JSONObject()
                    .put("zone", zone.name)
                    .put("reason", reason)
                    .put("candidates", candidates.joinToString { it.toString() })
                    .toString()
                File(directory, "meta.json").writeText(meta)
            }.onFailure { Log.w(TAG, "ISSUE_SAMPLE_SAVE_FAILED", it) }
            copy.recycle()
        }
    }

    private fun trim() {
        val directories = root.listFiles()?.filter(File::isDirectory)
            ?.sortedBy(File::lastModified).orEmpty()
        directories.take((directories.size - (MAX_PACKETS - 1)).coerceAtLeast(0))
            .forEach(File::deleteRecursively)
    }

    override fun close() {
        executor.shutdown()
    }

    private companion object {
        const val MAX_PACKETS = 50
        const val TAG = "CaptureProbe"
    }
}

internal data class RankCountsCandidate(
    val cards: com.example.cardcounter.domain.RankCounts,
    val minimumConfidence: Float,
    val cardCount: Int
)
