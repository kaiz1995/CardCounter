package com.example.cardcounter.capture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.vision.RankModelContract
import com.example.cardcounter.vision.YoloDetection
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * 只留存没能自动入账的证据，供后续人工核对；绝不保存整屏。
 * 出牌：无共识、被拒、暂停，每组是这一手交出的帧（最多 3 张 ROI png）+ meta.json。
 * 手牌：张数对不上、用户改过、确认失败、低把握，每组是手牌区截图或手牌画布 + meta.json，原因分开存。
 * 目录名带毫秒时间和自增序号，同一毫秒存两组也不会撞名。
 */
internal class IssueSampleStore(context: Context) : AutoCloseable {
    private val root = File(context.filesDir, "card_counter_issues")
    private val executor = Executors.newSingleThreadExecutor()
    private val sequence = AtomicLong()

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
            val directory = newDirectory("${zone.name}_$reason")
            copies.forEachIndexed { index, copy ->
                File(directory, "roi_$index.png").outputStream().use { out ->
                    check(copy.compress(Bitmap.CompressFormat.PNG, 100, out)) { "无法写入证据 ROI" }
                }
            }
            File(directory, "meta.json").writeText(metadata.toJson().toString())
        }
    }

    /**
     * 确认手牌时值得留证据的情况：[reason] 是 HAND_COUNT_MISMATCH（点数合计和数出来的张数对不上）、
     * HAND_EDITED（用户改过点数，或确认结果和模型合并结果不同）、HAND_CONFIRM_FAILED（确认被拒或停记），
     * 原因分开各存一组，和其他证据共用 50 组上限。存最近一帧的手牌区截图（1280x578 画布 y≥[HandRegions.YOLO_TOP]）
     * 和 [metadata]（见 [handEvidenceJson]）。[handArea] 这里同步拷一份，返回后调用方可以马上回收它。
     */
    fun saveHandEvidence(reason: String, handArea: Bitmap, metadata: JSONObject) {
        val copy = handArea.copy(Bitmap.Config.ARGB_8888, false) ?: run {
            Log.w(TAG, "ISSUE_EVIDENCE_SAVE_FAILED copy=null")
            return
        }
        submit(listOf(copy)) {
            root.mkdirs()
            trim()
            val directory = newDirectory("SELF_$reason")
            File(directory, "hand.png").outputStream().use { out ->
                check(copy.compress(Bitmap.CompressFormat.PNG, 100, out)) { "无法写入手牌区截图" }
            }
            File(directory, "meta.json").writeText(metadata.toString())
        }
    }

    /**
     * 手牌识别低把握或多帧合并比单帧多出牌时存一份训练样本：送进模型的手牌画布（原样，便于直接标注）和 meta.json。
     * [canvas] 归识别线程所有，这里同步拷一份再交给写盘线程，调用返回后识别线程可以马上回收它。
     */
    fun saveHandLowConfidence(
        canvas: Bitmap,
        timeMs: Long,
        revision: Long,
        scene: Long,
        triggers: List<String>,
        result: HandMergeResult,
        positionCount: Int?,
        template: RankCounts?
    ) {
        val copy = canvas.copy(Bitmap.Config.ARGB_8888, false) ?: run {
            Log.w(TAG, "ISSUE_EVIDENCE_SAVE_FAILED copy=null")
            return
        }
        val metadata = handLowConfidenceJson(
            timeMs, revision, scene, triggers, result, positionCount, template, copy.width
        )
        submit(listOf(copy)) {
            root.mkdirs()
            trim()
            val directory = newDirectory("SELF_$HAND_LOW_CONFIDENCE")
            File(directory, "hand_canvas.png").outputStream().use { out ->
                check(copy.compress(Bitmap.CompressFormat.PNG, 100, out)) { "无法写入手牌画布" }
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

    /** 新建一个证据目录：毫秒时间 + 自增序号；万一已存在就换下一个序号，不丢证据。只在写盘线程上调用。 */
    private fun newDirectory(tag: String): File {
        repeat(MAX_NAME_TRIES) {
            val directory = File(root, "${System.currentTimeMillis()}_${sequence.incrementAndGet()}_$tag")
            if (directory.mkdirs()) return directory
        }
        error("无法创建证据目录")
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
        const val MAX_NAME_TRIES = 100
        const val TAG = "CaptureProbe"
    }
}

/** 手牌点数合计和数出来的张数对不上（包括没数出来）时确认的证据原因。 */
internal const val HAND_COUNT_MISMATCH = "HAND_COUNT_MISMATCH"

/** 用户改过点数，或确认结果和模型合并结果不同的证据原因。 */
internal const val HAND_EDITED = "HAND_EDITED"

/** 确认被拒或停记的证据原因。 */
internal const val HAND_CONFIRM_FAILED = "HAND_CONFIRM_FAILED"

/**
 * 确认手牌时存的证据 meta。shown 是确认时显示的各点数张数；model 是模型多帧合并结果，modelDetections 是合并后
 * 每个牌位的检测框（rank/conf/cx/cy/w/h，坐标是 320 输入画布像素）；template 是模板匹配结果（模型已加载时不跑，为 null）；
 * edited 是用户改过的点数；outcome 是账本给的结果；draftVersion 是确认的草稿版本。
 * 手牌区截图的上沿是 areaTop（1280x578 画布坐标）。没有的字段一律写显式 null，不缺键。
 */
internal fun handEvidenceJson(
    reason: String,
    timeMs: Long,
    shown: RankCounts,
    total: Int,
    positionCount: Int?,
    topRowCount: Int?,
    bottomRowCount: Int?,
    model: RankCounts?,
    modelDetections: List<YoloDetection>,
    template: RankCounts?,
    edited: List<String>,
    outcome: String,
    draftVersion: Long
): JSONObject = JSONObject()
    .put("reason", reason)
    .put("timeMs", timeMs)
    .put("areaTop", HandRegions.YOLO_TOP)
    .put("shown", rankCountsJson(shown))
    .put("total", total)
    .put("positionCount", positionCount ?: JSONObject.NULL)
    .put("topRowCount", topRowCount ?: JSONObject.NULL)
    .put("bottomRowCount", bottomRowCount ?: JSONObject.NULL)
    .put("model", model?.let(::rankCountsJson) ?: JSONObject.NULL)
    .put("modelDetections", detectionsJson(modelDetections))
    .put("template", template?.let(::rankCountsJson) ?: JSONObject.NULL)
    .put("edited", JSONArray(edited))
    .put("outcome", outcome)
    .put("draftVersion", draftVersion)

/** 手牌识别低把握或合并多出牌时存的训练样本的原因。 */
internal const val HAND_LOW_CONFIDENCE = "HAND_LOW_CONFIDENCE"

/**
 * 低把握手牌样本的 meta：检测框坐标是模型输入（[RankModelContract.INPUT_SIZE] 见方）上的像素，
 * 手牌画布是 [canvasSize] 见方，标注时按 canvasSize / modelInputSize 换算。
 * frameDetections 是这一帧自己的检测框，slotDetections 是合并后每个牌位留下的那次检测。
 * template 在模型已加载时不跑模板匹配，写显式 null。
 */
internal fun handLowConfidenceJson(
    timeMs: Long,
    revision: Long,
    scene: Long,
    triggers: List<String>,
    result: HandMergeResult,
    positionCount: Int?,
    template: RankCounts?,
    canvasSize: Int
): JSONObject = JSONObject()
    .put("reason", HAND_LOW_CONFIDENCE)
    .put("triggers", JSONArray(triggers))
    .put("timeMs", timeMs)
    .put("handRevision", revision)
    .put("scene", scene)
    .put("canvasSize", canvasSize)
    .put("modelInputSize", RankModelContract.INPUT_SIZE)
    .put("frames", result.frames)
    .put("fellBack", result.fellBack)
    .put("single", rankCountsJson(result.single))
    .put("singleTotal", result.single.total())
    .put("merged", rankCountsJson(result.merged))
    .put("mergedTotal", result.merged.total())
    .put("positionCount", positionCount ?: JSONObject.NULL)
    .put("template", template?.let(::rankCountsJson) ?: JSONObject.NULL)
    .put("frameDetections", detectionsJson(result.frameDetections))
    .put("slotDetections", detectionsJson(result.slotDetections))

private fun detectionsJson(detections: List<YoloDetection>): JSONArray = JSONArray().apply {
    detections.forEach { detection ->
        put(
            JSONObject()
                .put("rank", detection.rank.name)
                .put("conf", detection.confidence.toDouble())
                .put("cx", detection.centerX.toDouble())
                .put("cy", detection.centerY.toDouble())
                .put("w", detection.width.toDouble())
                .put("h", detection.height.toDouble())
        )
    }
}

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
