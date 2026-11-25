package com.example.cardcounter.capture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.vision.YoloDetection
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import org.json.JSONArray
import org.json.JSONObject

/**
 * 只留存一手牌没能自动入账的证据（无共识、被拒、暂停），供后续人工核对；绝不保存整屏。
 * 每组一个目录：这一手交出的帧（最多 3 张 ROI png）+ meta.json。
 */
internal class IssueSampleStore(context: Context) : AutoCloseable {
    private val root = File(context.filesDir, "card_counter_issues")
    private val executor = Executors.newSingleThreadExecutor()

    /** [reason]：NO_CONSENSUS、REDUCER_REJECTED 或 PAUSED_<暂停原因>；[result] 是最终认定的牌，无共识时为 null。 */
    fun saveEvidence(
        bitmaps: List<Bitmap>,
        zone: PlayZone,
        reason: String,
        generation: Long,
        timeMs: Long,
        result: RankCounts?,
        candidates: List<RankCountsCandidate>
    ) {
        // 内存吃紧时 copy 会返回 null：拷不出来的帧不存，不能因为存证据把识别搞崩。
        val copies = bitmaps.takeLast(MAX_FRAMES).mapNotNull { it.copy(Bitmap.Config.ARGB_8888, false) }
        val metadata = evidenceMetadata(zone, reason, generation, timeMs, result, candidates)
        submit(copies) {
            root.mkdirs()
            trim()
            val directory = File(root, "${System.currentTimeMillis()}_${zone.name}_$reason")
            check(directory.mkdirs()) { "无法创建证据目录" }
            copies.forEachIndexed { index, copy ->
                File(directory, "roi_$index.png").outputStream().use { out ->
                    check(copy.compress(Bitmap.CompressFormat.PNG, 100, out)) { "无法写入证据 ROI" }
                }
            }
            File(directory, "meta.json").writeText(metadata.toJson().toString())
        }
    }

    /**
     * 用户在点数合计和数出来的张数对不上时确认手牌：存最近一帧的手牌区截图（1280x578 画布 y≥380）和 meta.json，
     * 事后核对是张数没数对还是点数识别错。reason 固定为 HAND_COUNT_MISMATCH，和其他证据共用 50 组上限。
     */
    fun saveHandCountMismatch(
        handArea: Bitmap,
        timeMs: Long,
        shown: RankCounts,
        total: Int,
        positionCount: Int?,
        topRowCount: Int?,
        bottomRowCount: Int?,
        model: RankCounts?,
        template: RankCounts,
        edited: List<String>
    ) {
        val copy = handArea.copy(Bitmap.Config.ARGB_8888, false) ?: run {
            Log.w(TAG, "ISSUE_EVIDENCE_SAVE_FAILED copy=null")
            return
        }
        val metadata = handCountMismatchJson(
            timeMs, shown, total, positionCount, topRowCount, bottomRowCount, model, template, edited
        )
        submit(listOf(copy)) {
            root.mkdirs()
            trim()
            val directory = File(root, "${System.currentTimeMillis()}_SELF_$HAND_COUNT_MISMATCH")
            check(directory.mkdirs()) { "无法创建证据目录" }
            File(directory, "hand.png").outputStream().use { out ->
                check(copy.compress(Bitmap.CompressFormat.PNG, 100, out)) { "无法写入手牌区截图" }
            }
            File(directory, "meta.json").writeText(metadata.toString())
        }
    }

    /**
     * 在写盘线程上存一组证据，写完回收 [copies]。服务退出后线程池已关，提交会被拒：
     * 这时这组证据不存，拷贝当场回收，不抛到调用方（调用方在识别或回调线程上，抛出去会崩）。
     */
    private fun submit(copies: List<Bitmap>, write: () -> Unit) {
        val task = Runnable {
            runCatching(write).onFailure { Log.w(TAG, "ISSUE_EVIDENCE_SAVE_FAILED", it) }
            copies.forEach(Bitmap::recycle)
        }
        try {
            executor.execute(task)
        } catch (error: RejectedExecutionException) {
            Log.w(TAG, "ISSUE_EVIDENCE_SAVE_FAILED store closed", error)
            copies.forEach(Bitmap::recycle)
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
        const val MAX_FRAMES = 3
        const val TAG = "CaptureProbe"
    }
}

/** 手牌点数合计和数出来的张数对不上时确认的证据原因。 */
internal const val HAND_COUNT_MISMATCH = "HAND_COUNT_MISMATCH"

/** shown 是确认时显示的各点数张数；model / template 是最近一次两路识别结果；edited 是用户改过的点数。 */
internal fun handCountMismatchJson(
    timeMs: Long,
    shown: RankCounts,
    total: Int,
    positionCount: Int?,
    topRowCount: Int?,
    bottomRowCount: Int?,
    model: RankCounts?,
    template: RankCounts,
    edited: List<String>
): JSONObject = JSONObject()
    .put("reason", HAND_COUNT_MISMATCH)
    .put("timeMs", timeMs)
    .put("shown", rankCountsJson(shown))
    .put("total", total)
    .put("positionCount", positionCount ?: JSONObject.NULL)
    .put("topRowCount", topRowCount ?: JSONObject.NULL)
    .put("bottomRowCount", bottomRowCount ?: JSONObject.NULL)
    .put("model", model?.let(::rankCountsJson) ?: JSONObject.NULL)
    .put("template", rankCountsJson(template))
    .put("edited", JSONArray(edited))

/** 投票里一帧的识别结果：点数计数、最低置信度和每个检测框。 */
internal data class RankCountsCandidate(
    val cards: RankCounts,
    val minimumConfidence: Float,
    val detections: List<YoloDetection>
)

internal fun evidenceMetadata(
    zone: PlayZone,
    reason: String,
    generation: Long,
    timeMs: Long,
    result: RankCounts?,
    candidates: List<RankCountsCandidate>
): EvidenceMetadata = EvidenceMetadata(zone.name, reason, generation, timeMs, result, candidates.map { candidate ->
    CandidateMetadata(
        candidate.cards, candidate.minimumConfidence, candidate.cards.total(),
        candidate.detections.map {
            DetectionMetadata(it.rank.name, it.confidence, it.centerX, it.centerY, it.width, it.height)
        }
    )
})

internal data class EvidenceMetadata(
    val zone: String,
    val reason: String,
    val generation: Long,
    val timeMs: Long,
    val result: RankCounts?,
    val candidates: List<CandidateMetadata>
)

internal data class CandidateMetadata(
    val cards: RankCounts,
    val minimumConfidence: Float,
    val cardCount: Int,
    val detections: List<DetectionMetadata>
)

internal data class DetectionMetadata(
    val rank: String,
    val confidence: Float,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float
)

private fun EvidenceMetadata.toJson(): JSONObject = JSONObject()
    .put("zone", zone)
    .put("reason", reason)
    .put("generation", generation)
    .put("timeMs", timeMs)
    .put("result", result?.let(::rankCountsJson) ?: JSONObject.NULL)
    .put("candidates", JSONArray().apply {
        candidates.forEach { candidate ->
            put(
                JSONObject()
                    .put("cards", rankCountsJson(candidate.cards))
                    .put("minimumConfidence", candidate.minimumConfidence.toDouble())
                    .put("cardCount", candidate.cardCount)
                    .put("detections", JSONArray().apply {
                        candidate.detections.forEach { detection ->
                            put(
                                JSONObject()
                                    .put("rank", detection.rank)
                                    .put("conf", detection.confidence.toDouble())
                                    .put("cx", detection.centerX.toDouble())
                                    .put("cy", detection.centerY.toDouble())
                                    .put("w", detection.width.toDouble())
                                    .put("h", detection.height.toDouble())
                            )
                        }
                    })
            )
        }
    })

internal fun rankCountsJson(cards: RankCounts): JSONObject = JSONObject(rankCountsMap(cards))

internal fun rankCountsMap(cards: RankCounts): Map<String, Int> = cards.asMap().mapKeys { it.key.name }
