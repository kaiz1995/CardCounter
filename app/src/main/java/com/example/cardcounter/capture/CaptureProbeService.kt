package com.example.cardcounter.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.Image
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.cardcounter.R
import com.example.cardcounter.domain.DispatchResult
import com.example.cardcounter.domain.RejectNote
import com.example.cardcounter.domain.resumePlan
import com.example.cardcounter.domain.HandConfirmGate
import com.example.cardcounter.domain.HandDraft
import com.example.cardcounter.domain.gate
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.domain.SessionPhase
import com.example.cardcounter.persistence.SessionRepository
import com.example.cardcounter.persistence.SessionStore
import com.example.cardcounter.recognition.GoujiPlayRules
import com.example.cardcounter.recognition.PlayRuleCheck
import com.example.cardcounter.service.OverlayService
import com.example.cardcounter.storage.DiagnosticsLog
import com.example.cardcounter.storage.LedgerCommand
import com.example.cardcounter.storage.SessionActor
import com.example.cardcounter.vision.OnnxRankDetector
import com.example.cardcounter.vision.ScreenScale
import com.example.cardcounter.vision.YoloDetection
import java.util.UUID

/** MediaProjection 采集服务：取帧、手牌锁定、六区出牌识别并经 SessionActor 入账。W5 会把识别与状态机拆进 :core。 */
class CaptureProbeService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val callbackThread = HandlerThread("capture-probe")
    private val rankThread = HandlerThread("rank-probe")
    private lateinit var callbackHandler: Handler
    private lateinit var rankHandler: Handler
    private lateinit var handRankMatcher: HandRankMatcher
    private lateinit var handCardPositionDetector: HandCardPositionDetector
    private lateinit var issueSampleStore: IssueSampleStore
    private var publicPlayYolo: OnnxRankDetector? = null
    private var lastRankProbeAt = 0L
    private var frameCount = 0L
    private var firstFrameAt = 0L
    private var lastReportAt = 0L
    private var lastReportFrames = 0L
    private var lastHandSignature: IntArray? = null
    private var lastHandSampleAt = 0L
    private var stableHandSamples = 0
    private var maxHandChangePercent = 0.0
    private val rankProbeGate = SingleFlightGate()
    private val stablePlayGate = StablePlayGate()
    private val zoneSignals = ZoneSignalTracker()
    private val roiEventCache = RoiPlayEventCache<RoiFrame>(
        isStill = { previous, current -> RoiFingerprint.isStill(previous.fingerprint, current.fingerprint) },
        discard = { it.bitmap?.recycle() }
    )
    private var lastRoiSampleAt = 0L
    private var restartPrimeDeadlineAt = 0L
    @Volatile private var captureGeneration = 0L
    @Volatile private var handRevision = 0L
    /**
     * 手牌画面编号：亮度指纹变化超过 STABLE_CHANGE_PERCENT（或新局后第一次取指纹）就加一，只在回调线程写。
     * 多帧合并按它分段：编号变了，累积的牌位全部作废。
     */
    @Volatile private var handScene = 0L
    /** 手牌多帧合并、张数投票和低把握样本限流：只在识别线程上用；新局和手牌确认时在识别线程上清空。 */
    private val handMerger = HandFrameMerger()
    private val positionVoter = HandPositionVoter()
    private val handSampleThrottle = HandSampleThrottle()
    /** 中途打开：本局不记，等下一局；新局重置时清掉。 */
    @Volatile private var gameSkipped = false
    /** 中途打开连续多帧判定器：仅统计 OUTSIDE 区，连续 5 次采样可见才判中途打开。 */
    private val midGameOpenDetector = MidGameOpenDetector()
    /** 开局换牌期门控：确认手牌或见过任意 OUTSIDE 区出牌圆标之后才算进入正式出牌阶段。 */
    private val openingPlayGate = OpeningPlayGate()
    private lateinit var sessionLedger: SessionRepository
    private lateinit var sessionActor: SessionActor
    private lateinit var diagnosticsLog: DiagnosticsLog
    private lateinit var diagnosticsStore: SessionStore

    override fun onCreate() {
        super.onCreate()
        handRankMatcher = HandRankMatcher(this)
        handCardPositionDetector = HandCardPositionDetector()
        issueSampleStore = IssueSampleStore(this)
        // 单写者：本服务生命周期内所有账本写入都经过它串行执行。
        sessionLedger = SessionRepository.get(this)
        // 单写者：读 phase 也走同一个 Repository 实例，杜绝临时实例 init 触发的旁路写盘。
        sessionActor = SessionActor(sessionLedger)
        diagnosticsLog = DiagnosticsLog()
        diagnosticsStore = SessionStore(this)
        diagnosticsStore.loadDiagnostics()?.let { json ->
            runCatching { diagnosticsLog.loadRecentJson(json) }
                .onFailure { Log.w(TAG, "DIAGNOSTICS_RESTORE_FAILED", it) }
        }
        publicPlayYolo = runCatching { OnnxRankDetector(this) }
            .onFailure { Log.w(TAG, "YOLO_DISABLED", it) }
            .getOrNull()
        callbackThread.start()
        rankThread.start()
        callbackHandler = Handler(callbackThread.looper)
        rankHandler = Handler(rankThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (intent?.action == ACTION_REQUEST_NEW_GAME) {
            callbackHandler.post { startNewGame() }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CONFIRM_HAND) {
            val clickedVersion = intent.getLongExtra(EXTRA_DRAFT_VERSION, -1L)
            callbackHandler.post { confirmHandFromPanel(clickedVersion) }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESUME) {
            val revertLast = intent.getBooleanExtra(EXTRA_REVERT_LAST, false)
            rankHandler.post { resumeSession(revertLast) }
            return START_NOT_STICKY
        }
        if (projection == null) startCapture(intent)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        display?.release()
        reader?.close()
        projection?.stop()
        display = null
        reader = null
        projection = null
        // 先在锁里标记退出并清掉手牌截图：之后识别线程不再写回，确认手牌也不再存证据。
        synchronized(this) {
            destroyed = true
            replaceHandProbe(null)
        }
        publicPlayYolo?.close()
        publicPlayYolo = null
        roiEventCache.reset(++captureGeneration, primeExistingTable = false)
        issueSampleStore.close()
        sessionActor.shutdown()
        CaptureProbeStatus.stopped()
        callbackThread.quitSafely()
        rankThread.quitSafely()
        Log.i(TAG, "CAPTURE_STOPPED")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCapture(intent: Intent?) {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            CaptureProbeStatus.failed("未获得截屏授权")
            Log.e(TAG, "CAPTURE_FAILED: missing projection permission")
            stopSelf()
            return
        }
        try {
            CaptureProbeStatus.started()
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            projection = requireNotNull(manager.getMediaProjection(resultCode, data)) {
                "MediaProjection 不可用"
            }
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            frameCount = 0L
            firstFrameAt = 0L
            lastReportAt = 0L
            lastReportFrames = 0L
            // 账本里已有手牌或外面出牌 = 接着上次的这一局记，桌上已有的牌不重复记；
            // 否则是全新的一局，牌桌第一次出现时判断是不是中途打开。
            val resumed = sessionLedger.state.let { it.handConfirmed || it.outsidePlayedCount > 0 }
            resetSessionTracking(SessionTrackingReset.onCaptureStart(resumed))
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).also {
                it.setOnImageAvailableListener(::onImageAvailable, callbackHandler)
            }
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "CAPTURE_STOPPED_BY_SYSTEM")
                    stopSelf()
                }
            }, callbackHandler)
            display = projection!!.createVirtualDisplay(
                "CardCounterProbe",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface,
                null,
                callbackHandler
            )
            Log.i(TAG, "CAPTURE_STARTED requested=${width}x${height} dpi=${metrics.densityDpi}")
        } catch (error: Exception) {
            CaptureProbeStatus.failed(error.message ?: error.javaClass.simpleName)
            Log.e(TAG, "CAPTURE_FAILED", error)
            stopSelf()
        }
    }

    private fun onImageAvailable(imageReader: ImageReader) {
        val image = imageReader.acquireLatestImage() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            frameCount += 1
            if (firstFrameAt == 0L) {
                firstFrameAt = now
                lastReportAt = now
                Log.i(TAG, "FIRST_FRAME size=${image.width}x${image.height} format=${image.format}")
            }
            if (now - lastHandSampleAt >= HAND_SAMPLE_INTERVAL_MS) {
                val signature = handSignature(image)
                val change = handChangePercent(lastHandSignature, signature)
                val stillScene = change != null && change <= STABLE_CHANGE_PERCENT
                stableHandSamples = if (stillScene) stableHandSamples + 1 else 0
                if (!stillScene) handScene += 1
                maxHandChangePercent = maxOf(maxHandChangePercent, change ?: 0.0)
                CaptureProbeStatus.handCandidate(change, maxHandChangePercent, stableHandSamples)
                lastHandSignature = signature
                lastHandSampleAt = now
            }
            captureRoiEvents(image)
            if (!gameSkipped && shouldProbeHand(sessionLedger.state.handConfirmed, stableHandSamples) &&
                now - lastRankProbeAt >= RANK_PROBE_INTERVAL_MS &&
                rankProbeGate.tryAcquire()
            ) {
                // 截图在交给识别线程之前出错、或识别线程已停（post 返回 false），都要在这里回收并放开闸门。
                var handArea: Bitmap? = null
                var handYoloFrame: Bitmap? = null
                var posted = false
                try {
                    val modelLoaded = publicPlayYolo != null
                    // 模板只在模型没加载时用：模型已加载就不再每帧取墨迹、跑模板匹配。
                    val ink = if (modelLoaded) null else handRankMatcher.snapshot(image)
                    val luma = handCardPositionDetector.snapshot(image)
                    val frames = handFrames(image, withCanvas = modelLoaded)
                    val area = frames.area.also { handArea = it }
                    val yoloFrame = frames.canvas.also { handYoloFrame = it }
                    val revision = handRevision
                    val scene = handScene
                    posted = rankHandler.post {
                        var handAreaKept = false
                        try {
                            if (gameSkipped || revision != handRevision || sessionLedger.state.handConfirmed) return@post
                            val rowCounts = handCardPositionDetector.rowCounts(luma)
                            val positionCount = handCardPositionDetector.detect(rowCounts)
                            val yoloCards = yoloFrame?.let(::detectYoloCards)
                            // 模型已加载但这一帧出错或 0 个框：本帧不进合并、不刷新草稿，也不拿模板顶替。
                            val usable = yoloCards?.takeIf { it.detections.isNotEmpty() }
                            // 手牌画面停稳期间把每一帧的检测框累积起来：把握度低的牌时有时无，只取最新一帧会漏牌。
                            val merge = usable?.let { handMerger.add(scene, it.detections) }
                            var templateCards: RankCounts? = null
                            val cards = chooseHandCards(modelLoaded, merge?.merged) {
                                handRankMatcher.detect(handRankMatcher.prepare(requireNotNull(ink))).also { templateCards = it }
                            }
                            if (cards == null) {
                                Log.i(TAG, "HAND_FRAME_SKIPPED yolo=$yoloCards")
                                return@post
                            }
                            // 数出来的张数和点数一样多帧投票：同一停稳画面里取众数，不用最新单帧。
                            val votedPosition = positionVoter.add(scene, positionCount)
                            handAreaKept = publishHandProbe(
                                revision, cards, votedPosition,
                                HandProbe(area, rowCounts, merge?.merged, merge?.slotDetections.orEmpty(), templateCards)
                            )
                            if (!handAreaKept) return@post
                            HandPanelState.changed()
                            Log.i(
                                TAG,
                                "HAND_CANDIDATE total=${cards.total()} single=${merge?.single?.total()} " +
                                    "merged=${merge?.merged?.total()} frames=${merge?.frames} " +
                                    "positionCount=$positionCount voted=$votedPosition rows=${rowCounts.first}/${rowCounts.second} " +
                                    "yolo=$yoloCards template=$templateCards cards=$cards"
                            )
                            if (merge != null) {
                                if (merge.fellBack) {
                                    Log.w(TAG, "HAND_MERGE_OVER_INVENTORY fallback to single frame merged=${merge.single}")
                                }
                                saveHandSample(yoloFrame, revision, scene, merge, votedPosition, templateCards)
                            }
                        } finally {
                            if (!handAreaKept) area.recycle()
                            yoloFrame?.recycle()
                            rankProbeGate.release()
                        }
                    }
                    if (posted) lastRankProbeAt = now
                } finally {
                    if (!posted) {
                        handArea?.recycle()
                        handYoloFrame?.recycle()
                        rankProbeGate.release()
                    }
                }
            }
            if (now - lastReportAt >= REPORT_INTERVAL_MS) {
                val elapsed = (now - lastReportAt).coerceAtLeast(1L)
                val fps = (frameCount - lastReportFrames) * 1_000.0 / elapsed
                CaptureProbeStatus.frame(image.width, image.height, frameCount, fps)
                updateNotification(CaptureProbeStatus.snapshot().summary())
                Log.i(
                    TAG,
                    "FRAME_RATE size=${image.width}x${image.height} fps=${"%.1f".format(fps)} frames=$frameCount"
                )
                lastReportAt = now
                lastReportFrames = frameCount
            }
        } finally {
            image.close()
        }
    }

    /**
     * 手牌识别出现“合并比单帧多出牌”或有检测框把握度低于 0.5 时，存一份训练样本（送进模型的手牌画布 + meta.json）。
     * 同一画面同一原因只存一次、每局有上限；画布由识别线程拥有，存档在这里同步拷贝，返回后识别线程可以马上回收它。
     */
    private fun saveHandSample(
        canvas: Bitmap?,
        revision: Long,
        scene: Long,
        merge: HandMergeResult,
        positionCount: Int?,
        template: RankCounts?
    ) {
        val triggers = merge.sampleTriggers()
        if (canvas == null || triggers.isEmpty() || !handSampleThrottle.admit(scene, triggers)) return
        if (canvas.isRecycled) return
        issueSampleStore.saveHandLowConfidence(
            canvas, SystemClock.elapsedRealtime(), revision, scene, triggers, merge, positionCount, template
        )
        Log.i(TAG, "HAND_SAMPLE_SAVED triggers=$triggers single=${merge.single.total()} merged=${merge.merged.total()}")
    }

    private fun startForegroundCompat() {
        createChannel()
        val notification = notification("正在等待屏幕帧，不保存完整录像")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** 仅取底部候选区的低分辨率亮度指纹；不保存帧，也不识别牌面。 */
    private fun handSignature(image: Image): IntArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val area = HandRegions.signatureArea(image.width, image.height)
        val left = area.left
        val right = area.right
        val top = area.top
        val result = IntArray(HAND_SIGNATURE_COLUMNS * HAND_SIGNATURE_ROWS)
        for (index in result.indices) {
            val column = index % HAND_SIGNATURE_COLUMNS
            val row = index / HAND_SIGNATURE_COLUMNS
            val x = left + (right - left - 1) * column / (HAND_SIGNATURE_COLUMNS - 1)
            val y = top + (image.height - top - 1) * row / (HAND_SIGNATURE_ROWS - 1)
            val offset = y * plane.rowStride + x * plane.pixelStride
            val red = buffer.get(offset).toInt() and 0xff
            val green = buffer.get(offset + 1).toInt() and 0xff
            val blue = buffer.get(offset + 2).toInt() and 0xff
            result[index] = (red * 30 + green * 59 + blue * 11) / 100
        }
        return result
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    /** 与屏幕缩放同一套换算，直接返回 0xRRGGBB，不分配数组（圆标和指纹每 50ms 要取几千个点）。 */
    private fun pixelRgb(image: Image, canonicalX: Int, canonicalY: Int): Int {
        val plane = image.planes[0]
        val x = canonicalX * image.width / 1_280
        val y = canonicalY * image.height / 578
        val offset = y * plane.rowStride + x * plane.pixelStride
        val buffer = plane.buffer
        return ((buffer.get(offset).toInt() and 0xff) shl 16) or
            ((buffer.get(offset + 1).toInt() and 0xff) shl 8) or
            (buffer.get(offset + 2).toInt() and 0xff)
    }

    /**
     * 停记恢复：只有账本正处于停记才做（悬浮窗按钮可能连点或点在过期的画面上）。
     * [revertLast] 为 true 时先撤销最近一手外面出牌（没有可撤的就只恢复）；为 false 时只恢复、不撤销。
     * 确认手牌引起的停记，手牌没进账，恢复后手牌回到可编辑、可重新确认。
     */
    private fun resumeSession(revertLast: Boolean) {
        val plan = resumePlan(sessionLedger.state, revertLast)
        if (plan == null) {
            Log.i(TAG, "RESUME_IGNORED not paused")
            return
        }
        val lastOutside = plan.revertTarget
        if (lastOutside != null) {
            val revertResult = sessionActor.post(LedgerCommand.Revert(lastOutside.id))
            if (revertResult is DispatchResult.Accepted) {
                stablePlayGate.noteReverted(lastOutside.seat)
                val zone = PlayZone.entries.first { it.seat == lastOutside.seat }
                recordDiagnostics(
                    lastOutside.id,
                    zone,
                    quantity = lastOutside.cards.total(),
                    rankTotal = lastOutside.cards.total(),
                    decision = DiagnosticsLog.Decision.ACCEPTED,
                    reason = "REVERT_LAST_OUTSIDE_PLAY"
                )
            }
        }
        val resumeResult = sessionActor.post(LedgerCommand.Resume)
        HandPanelState.rejectNote = null
        Log.i(TAG, "RESUME revertLast=$revertLast reverted=${lastOutside?.id} resume=${outcomeOf(resumeResult)}")
        HandPanelState.changed()
        OverlayService.refresh(this)
    }

    /**
     * 手动新局：只重置，不锁手牌。账本开新局、手牌草稿清空，手牌识别接着在后台跑，等用户在悬浮窗上确认。
     * 新局不当作中途打开：这是用户主动开的。桌上已有的牌（上一局残留）当作旧牌不记，
     * 每个出牌区清空一次之后才开始记新出的牌。
     */
    private fun startNewGame() {
        sessionActor.post(LedgerCommand.NewSession())
        resetSessionTracking(SessionTrackingReset.onManualNewGame())
        OverlayService.refresh(this)
        Log.i(TAG, "MANUAL_NEW_GAME")
    }

    /**
     * 用户在悬浮窗上点“确认手牌”：所见即所确认。[clickedVersion] 是点击那一刻屏幕上显示的草稿版本，
     * 处理时草稿已经换了（后台识别刚好刷新、或又改过）就不确认，提示重新核对。
     * 草稿、手牌截图和检测框在同一把锁里一次取出，和 [publishHandProbe] 写入的是同一帧；锁外只用这份冻结的数据。
     * 点数合计和数出来的张数对不上、用户改过点数或确认结果和模型合并结果不同、确认被拒或停记时，
     * 各存一组证据，原因分开写（HAND_COUNT_MISMATCH / HAND_EDITED / HAND_CONFIRM_FAILED）。
     */
    private fun confirmHandFromPanel(clickedVersion: Long) {
        // 和 replaceHandProbe 用同一把锁：识别线程换下旧截图时会 recycle，截图要在锁里拷好，否则会拷到已回收的截图而崩溃。
        val frozen = synchronized(this) {
            val probe = handProbe
            val copy = if (destroyed) null else probe?.area?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
            FrozenHand(HandPanelState.draft.snapshot(), probe, copy)
        }
        try {
            val draft = frozen.draft
            val data = when (val gate = draft.gate(clickedVersion)) {
                is HandConfirmGate.Ready -> gate.data
                HandConfirmGate.Blocked -> return
                HandConfirmGate.Stale -> {
                    HandPanelState.rejectNote = RejectNote("手牌刚刚更新了，请核对后再确认", draft.version)
                    HandPanelState.changed()
                    Log.i(TAG, "SELF_HAND_CONFIRM_STALE clicked=$clickedVersion current=${draft.version}")
                    return
                }
            }
            val result = sessionActor.post(LedgerCommand.ConfirmHand(data.cards, data.handCount))
            HandPanelState.rejectNote = (result as? DispatchResult.Rejected)?.reason?.let { RejectNote(it, draft.version) }
            // 手牌确认后清空多帧累积；确认被拒时手牌仍是未确认，下一帧会从头累积，不会错。
            if (result is DispatchResult.Accepted) {
                rankHandler.post {
                    handMerger.reset()
                    positionVoter.reset()
                    handSampleThrottle.reset()
                }
            }
            val probe = frozen.probe
            val area = frozen.area
            val edited = draft.edited.map { it.name }.sorted()
            val reasons = buildList {
                if (data.handCount != data.cards.total()) add(HAND_COUNT_MISMATCH)
                if (edited.isNotEmpty() || (probe?.model != null && probe.model != data.cards)) add(HAND_EDITED)
                if (result is DispatchResult.Rejected || result is DispatchResult.Paused) add(HAND_CONFIRM_FAILED)
            }
            if (reasons.isNotEmpty()) {
                if (area != null && probe != null) {
                    reasons.forEach { reason ->
                        issueSampleStore.saveHandEvidence(
                            reason, area,
                            handEvidenceJson(
                                reason, SystemClock.elapsedRealtime(), data.cards, data.cards.total(), draft.positionCount,
                                probe.rows.first, probe.rows.second, probe.model, probe.modelDetections, probe.template,
                                edited, outcomeOf(result), draft.version
                            )
                        )
                    }
                } else {
                    Log.w(TAG, "HAND_EVIDENCE_SKIPPED no hand area reasons=$reasons")
                }
            }
            HandPanelState.changed()
            OverlayService.refresh(this)
            Log.i(
                TAG,
                "SELF_HAND_CONFIRM total=${data.cards.total()} positionCount=${data.handCount} " +
                    "top=${probe?.rows?.first} bottom=${probe?.rows?.second} " +
                    "edited=$edited version=${draft.version} reasons=$reasons " +
                    "cards=${data.cards} result=${outcomeOf(result)}"
            )
        } finally {
            frozen.area?.recycle()
        }
    }

    private fun outcomeOf(result: DispatchResult): String = when (result) {
        is DispatchResult.Accepted -> "ACCEPTED"
        is DispatchResult.DuplicateIgnored -> "DUPLICATE_IGNORED"
        is DispatchResult.Rejected -> "REJECTED:${result.reason}"
        is DispatchResult.Paused -> "PAUSED:${result.reason.name}"
    }

    /** 点击确认时一次冻结的草稿、最近一帧识别和手牌区截图拷贝（拷贝由确认流程回收）。 */
    private class FrozenHand(val draft: HandDraft, val probe: HandProbe?, val area: Bitmap?)

    /**
     * 最近一次手牌识别时的手牌区截图和两排数出来的张数，确认对不上时存证据用。
     * 识别线程写、回调线程读、新局和退出时清空，读写都在服务实例这把锁里。
     */
    private class HandProbe(
        val area: Bitmap,
        val rows: Pair<Int?, Int?>,
        /** 模型多帧合并结果；这一帧没用模型（模型没加载）为 null。 */
        val model: RankCounts?,
        /** 合并后每个牌位留下的检测框（rank/conf/cx/cy/w/h），证据里原样存。 */
        val modelDetections: List<YoloDetection>,
        /** 模板匹配结果；模型已加载时不跑模板，为 null。 */
        val template: RankCounts?
    )

    @Volatile private var handProbe: HandProbe? = null

    /** 服务已退出：识别线程队列里剩下的任务不再写回草稿和截图，也不再存证据。只在服务实例的锁里写。 */
    @Volatile private var destroyed = false

    @Synchronized
    private fun replaceHandProbe(next: HandProbe?) {
        val old = handProbe
        handProbe = next
        if (old != null && old !== next) old.area.recycle()
    }

    /**
     * 识别线程写回这一手的结果：检查“还是这一局、还没确认、服务没退出”和写回草稿、截图在同一把锁里做完，
     * 新局重置也拿这把锁，上一局的结果就写不进新局。写回了返回 true（截图归 handProbe 管）。
     */
    @Synchronized
    private fun publishHandProbe(revision: Long, cards: RankCounts, positionCount: Int?, probe: HandProbe): Boolean {
        if (destroyed || revision != handRevision || sessionLedger.state.handConfirmed) return false
        HandPanelState.draft.refresh(cards, positionCount)
        replaceHandProbe(probe)
        return true
    }

    /** 新局：作废识别线程手里的旧结果，清掉上一局的手牌截图和草稿。 */
    @Synchronized
    private fun resetHandProbe() {
        handRevision += 1
        // 上一局的手牌截图不能留给新局当证据，也别一直占着内存。
        replaceHandProbe(null)
        HandPanelState.reset()
    }

    /** 一次手牌取图：[area] 是 1280×578 画布 y≥[HandRegions.YOLO_TOP] 的手牌区（存证据用），[canvas] 是送模型的 870×870 手牌画布。 */
    private class HandFrames(val area: Bitmap, val canvas: Bitmap?)

    /**
     * 整屏区域平均缩到 1280×578 画布，和训练、评测一致（[ScreenScale]）。证据截图和手牌画布取自同一次缩放，
     * 证据图里的像素就是模型看到的像素，上沿和 YOLO 取图区同为 [HandRegions.YOLO_TOP]，事后能原样复跑模型。
     */
    private fun handFrames(image: Image, withCanvas: Boolean): HandFrames {
        val frame = image.toRaster()
        val width = ScreenScale.CANVAS_WIDTH
        val top = HandRegions.YOLO_TOP
        val height = ScreenScale.CANVAS_HEIGHT - top
        val region = ScreenScale.canvasRegion(frame, 0, top, width, height)
        val area = Bitmap.createBitmap(region, width, height, Bitmap.Config.ARGB_8888)
        val canvas = if (withCanvas) {
            val side = HandRegions.YOLO_RIGHT - HandRegions.YOLO_LEFT
            val pixels = ScreenScale.handCanvasFromRegion(
                region, 0, top, width,
                HandRegions.YOLO_LEFT, HandRegions.YOLO_TOP, HandRegions.YOLO_RIGHT, HandRegions.YOLO_BOTTOM
            )
            Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
        } else null
        return HandFrames(area, canvas)
    }

    /**
     * 截屏回调仅缓存小 ROI；牌面连续停稳后才推理。没停稳的一手在被新牌直接盖上、牌桌被挡住、
     * 清桌时也会交出推理（交出规则见 RoiPlayEventCache）。
     */
    private fun captureRoiEvents(image: Image) {
        if (gameSkipped) return
        val raster = image.toRaster()
        val tableVisible = isTableVisible(raster)
        val generation = captureGeneration
        val now = SystemClock.elapsedRealtime()
        val takeSample = now - lastRoiSampleAt >= ROI_SAMPLE_INTERVAL_MS
        if (!tableVisible && restartPrimeDeadlineAt > 0L && now >= restartPrimeDeadlineAt) {
            roiEventCache.releasePrime(generation)
            restartPrimeDeadlineAt = 0L
            Log.i(TAG, "PLAY_GATE_PRIME_RELEASED_OUTSIDE_TABLE")
        }
        // 中途打开、圆标、指纹、截图、清桌计数全按同一个 50ms 采样节拍推进（与电脑回放一致）；
        // 非采样帧不能推进清桌计数或中途打开计数，否则瞬时动画帧会误判。
        if (!takeSample) return
        lastRoiSampleAt = now
        val zoneStates = if (tableVisible) playZoneStates(raster) else emptyMap()
        val ledger = sessionLedger.state
        when (midGameOpenDetector.observe(ledger.handConfirmed, ledger.outsidePlayedCount, tableVisible, zoneStates)) {
            MidGameCheckResult.PENDING -> Unit
            MidGameCheckResult.CLEAR -> Unit
            MidGameCheckResult.SKIP -> {
                val visibleOutside = PlayZone.entries.filter {
                    it.source == PlaySource.OUTSIDE && zoneStates[it] == ZoneVisibility.CARD
                }
                val firstZone = visibleOutside.firstOrNull() ?: PlayZone.TOP
                val evidenceBitmaps = visibleOutside.take(3).map { publicPlayBitmap(image, it) }
                try {
                    issueSampleStore.saveEvidence(
                        evidenceBitmaps, firstZone, "OPENED_MID_GAME_SKIP",
                        generation, now, null, emptyList()
                    )
                } finally {
                    evidenceBitmaps.forEach(Bitmap::recycle)
                }
                recordDiagnostics(
                    null, firstZone, quantity = null, rankTotal = null,
                    decision = DiagnosticsLog.Decision.PAUSED, reason = "OPENED_MID_GAME_SKIP"
                )
                gameSkipped = true
                HandPanelState.skipped = true
                HandPanelState.changed()
                OverlayService.refresh(this)
                Log.i(TAG, "OPENED_MID_GAME_SKIP visibleZones=$visibleOutside")
                return
            }
        }
        val onTable = PlayZone.entries.filterTo(HashSet()) {
            tableVisible && isPlayVisible(zoneStates[it] == ZoneVisibility.CARD)
        }
        PlayZone.entries.forEach { zone ->
            val state = if (tableVisible) zoneStates[zone] else null
            // 局域被压暗面板等遮挡（OCCLUDED）时，按该区桌面不可见处理：不推进清桌、不交出兜底帧、空桌计时归零。
            val zoneTableVisible = tableVisible && state != ZoneVisibility.OCCLUDED
            val visible = zoneTableVisible && isPlayVisible(state == ZoneVisibility.CARD)
            val frame = if (visible && roiEventCache.needsFrame(zone, generation)) {
                val probe = RoiFrame(null, RoiFingerprint.of(zone) { x, y -> pixelRgb(image, x, y) })
                // 已交出且画面没变：只要指纹，不再截 320 图送识别；画面一变，这一帧是下一段静止的起点，要带图。
                if (roiEventCache.isRepeat(zone, generation, probe)) probe else probe.copy(bitmap = publicPlayBitmap(image, zone))
            } else {
                null
            }
            roiEventCache.observe(zone, zoneTableVisible, visible, frame, generation)?.let { finalized ->
                Log.i(
                    TAG,
                    "ROI_FINALIZED zone=${finalized.zone} frames=${finalized.frames.size} reason=${finalized.reason}"
                )
                rankHandler.post { finalizeRoiEvent(finalized, now) }
            }
            // “出牌”圆标和清桌计时放在交出之后看：清桌这次采样兜底交出的入账先 post，一定排在这次的信号前面。
            // 牌桌或本区被遮挡时也要采样（空桌计时和圆标连续计数在里面归零），圆标检测本身只在本区桌面可见时做。
            if (zone.source == PlaySource.OUTSIDE) {
                val shown = zoneTableVisible && TurnBadge.isShown(zone) { x, y -> pixelRgb(image, x, y) }
                val zoneEmpty = zoneTableVisible && !visible && !roiEventCache.hasUndelivered(zone, generation)
                val signals = zoneSignals.sample(
                    zone,
                    badgeShown = shown,
                    cleared = roiEventCache.isCleared(zone, generation),
                    zoneEmpty = zoneEmpty,
                    tableVisible = zoneTableVisible,
                    nowMs = now
                )
                if (signals.any) {
                    if (signals.turned) Log.i(TAG, "TURN_BADGE zone=$zone")
                    if (signals.longCleared) Log.i(TAG, "LONG_CLEAR_UNLOCK zone=$zone")
                    rankHandler.post {
                        if (generation != captureGeneration) return@post
                        val retries = routeZoneSignals(
                            zone, signals, now, stablePlayGate, openingPlayGate, sessionLedger.state.handConfirmed
                        ) { it in onTable }
                        replaySkippedPlays(retries, now)
                    }
                }
            }
        }
    }

    private fun finalizeRoiEvent(finalized: RoiPlayEventCache.Finalized<RoiFrame>, sampleTimeMs: Long) {
        try {
            if (finalized.generation != captureGeneration || gameSkipped) return
            // 自己出的牌完全不管：不识别、不记、不显示。
            if (finalized.zone.source == PlaySource.SELF) return
            // 只拿牌停稳之后的最后几帧投票，不带上牌还在滑入时的画面（与 eval 的 takeLast 一致）。
            val voted = finalized.frames.takeLast(FINALIZE_FRAME_COUNT)
            val candidates = voted.mapNotNull { frame ->
                frame.bitmap?.let(::detectYoloCards)?.let {
                    RankCountsCandidate(it.cards, it.minimumConfidence, it.detections)
                }
            }
            val voteOutcome = PlayFrameVote.resolve(candidates.map { it.detections })
            val cards = voteOutcome?.cards
            if (cards == null) {
                saveEvidence(finalized, voted, "NO_CONSENSUS", null, candidates)
                // 无共识留证据并记 RETRY，不静默丢弃。
                recordDiagnostics(
                    null, finalized.zone,
                    quantity = null,
                    rankTotal = candidates.maxOfOrNull { it.cards.total() },
                    decision = DiagnosticsLog.Decision.RETRY,
                    reason = "NO_CONSENSUS"
                )
                Log.w(TAG, "ROI_NO_CONSENSUS zone=${finalized.zone} candidates=$candidates")
                return
            }
            val ruleCheck = GoujiPlayRules.check(cards)
            if (ruleCheck is PlayRuleCheck.Suspicious) {
                saveEvidence(finalized, voted, "RULE_${ruleCheck.code}", cards, candidates)
                recordDiagnostics(
                    null, finalized.zone,
                    quantity = cards.total(),
                    rankTotal = cards.total(),
                    decision = DiagnosticsLog.Decision.RETRY,
                    reason = "${ruleCheck.code}:${ruleCheck.detail}"
                )
                Log.w(TAG, "ROI_RULE_SUSPICIOUS zone=${finalized.zone} code=${ruleCheck.code} cards=$cards")
                return
            }
            val ledgerState = sessionLedger.state
            if (ledgerState.phase != SessionPhase.ACTIVE) {
                // 暂停期间交出的牌不入账，但要留诊断记录（与 eval 的 NOT_ACTIVE 一致）。
                recordDiagnostics(
                    null, finalized.zone,
                    quantity = cards.total(),
                    rankTotal = cards.total(),
                    decision = DiagnosticsLog.Decision.RETRY,
                    reason = "NOT_ACTIVE"
                )
                Log.i(TAG, "NOT_ACTIVE zone=${finalized.zone} cards=$cards")
                return
            }
            if (!openingPlayGate.isReady(ledgerState.handConfirmed)) {
                // 记下这一手：开局门稍后因圆标打开时，桌上还摆着的会补记。
                openingPlayGate.notePrePlaySkipped(finalized.zone, cards, sampleTimeMs)
                saveEvidence(finalized, voted, "PRE_PLAY_SKIPPED", cards, candidates)
                recordDiagnostics(
                    null, finalized.zone,
                    quantity = cards.total(),
                    rankTotal = cards.total(),
                    decision = DiagnosticsLog.Decision.RETRY,
                    reason = "PRE_PLAY_SKIPPED"
                )
                Log.i(TAG, "PRE_PLAY_SKIPPED zone=${finalized.zone} cards=$cards")
                return
            }
            admitPlay(
                finalized.zone, cards, voteOutcome.mergedFromDisagreement, sampleTimeMs,
                finalizeReason = finalized.reason.name
            ) { reason ->
                saveEvidence(finalized, voted, reason, cards, candidates)
            }
        } finally {
            finalized.frames.forEach { it.bitmap?.recycle() }
        }
    }

    /**
     * 一手已经过规则校验、开局门放行的出牌入账：去重门检查 → 单写者账本 → 账本接受后提交去重门。
     * 正常交出和开局门因圆标打开后补记共用；补记时 [evidence] 什么都不存（那一手的截图早已回收）。
     */
    private fun admitPlay(
        zone: PlayZone,
        cards: RankCounts,
        merged: Boolean,
        sampleTimeMs: Long,
        finalizeReason: String? = null,
        evidence: (String) -> Unit
    ) {
        val stable = when (val gateDecision = stablePlayGate.check(zone, cards, sampleTimeMs)) {
            is PlayGateDecision.Accept -> gateDecision.play
            is PlayGateDecision.Suppressed -> {
                when (gateDecision.reason) {
                    PlaySuppressionReason.SUBSET_OR_SUPERSET -> {
                        evidence("PLAY_SUBSET_SUPERSET_SUPPRESSED")
                        recordDiagnostics(
                            null, zone,
                            quantity = cards.total(),
                            rankTotal = cards.total(),
                            decision = DiagnosticsLog.Decision.DUPLICATE,
                            reason = "PLAY_SUBSET_SUPERSET_SUPPRESSED"
                        )
                        Log.i(TAG, "PLAY_SUBSET_SUPERSET_SUPPRESSED zone=${zone} cards=$cards")
                    }
                    PlaySuppressionReason.EXACT_DUPLICATE -> {
                        recordDiagnostics(
                            null, zone,
                            quantity = cards.total(),
                            rankTotal = cards.total(),
                            decision = DiagnosticsLog.Decision.DUPLICATE,
                            reason = "PLAY_DUPLICATE_SUPPRESSED"
                        )
                        Log.i(TAG, "PLAY_DUPLICATE_SUPPRESSED zone=${zone} cards=$cards")
                    }
                    PlaySuppressionReason.EMPTY -> Unit
                }
                return
            }
        }
        recordDiagnostics(
            null, zone,
            quantity = stable.cards.total(),
            rankTotal = stable.cards.total(),
            decision = DiagnosticsLog.Decision.STABLE,
            reason = finalizeReason
        )
        // 单写者派发：evidenceId 兼作账本事件 id，重试不会重复扣牌。
        val command = stable.toLedgerCommand()
        val result = sessionActor.post(command)
        recordDiagnostics(
            command.evidenceId, zone,
            quantity = stable.cards.total(),
            rankTotal = stable.cards.total(),
            decision = DiagnosticsLog.Decision.forResult(result),
            reason = reasonOf(result)
        )
        when (result) {
            is DispatchResult.Accepted -> {
                stablePlayGate.commitAccepted(zone, stable.cards, sampleTimeMs) {
                    roiEventCache.isCleared(it, captureGeneration)
                }
                OverlayService.refresh(this)
                Log.i(
                    TAG,
                    "FORMAL_PLAY source=${zone.source} zone=${zone} " +
                        "cards=$cards merged=${merged}"
                )
            }
            is DispatchResult.DuplicateIgnored -> {
                OverlayService.refresh(this)
            }
            is DispatchResult.Rejected -> {
                evidence("REDUCER_REJECTED")
                OverlayService.refresh(this)
                Log.w(TAG, "PLAY_REJECTED zone=${zone} reason=${result.reason}")
            }
            is DispatchResult.Paused -> {
                evidence("PAUSED_${result.reason.name}")
                OverlayService.refresh(this)
                Log.w(TAG, "PLAY_PAUSED zone=${zone} reason=${result.reason}")
            }
        }
    }

    /** 开局门因圆标打开后，补记门控打开前交出过、牌还在桌上的几手：走和正常交出一样的去重门、账本。 */
    private fun replaySkippedPlays(plays: List<OpeningPlayGate.SkippedPlay>, sampleTimeMs: Long) {
        for (i in plays.indices) {
            if (gameSkipped) return
            if (sessionLedger.state.phase != SessionPhase.ACTIVE) {
                for (j in i until plays.size) {
                    val skipped = plays[j]
                    recordDiagnostics(
                        null, skipped.zone,
                        quantity = skipped.cards.total(),
                        rankTotal = skipped.cards.total(),
                        decision = DiagnosticsLog.Decision.RETRY,
                        reason = "NOT_ACTIVE"
                    )
                    Log.i(TAG, "NOT_ACTIVE zone=${skipped.zone} cards=${skipped.cards}")
                }
                return
            }
            val play = plays[i]
            Log.i(TAG, "PRE_PLAY_REPLAYED zone=${play.zone} cards=${play.cards}")
            admitPlay(play.zone, play.cards, merged = false, sampleTimeMs = sampleTimeMs) { }
        }
    }

    /** 把这一手交出的帧和每帧的识别结果存成证据；无共识、被拒、暂停都走这里。 */
    private fun saveEvidence(
        finalized: RoiPlayEventCache.Finalized<RoiFrame>,
        voted: List<RoiFrame>,
        reason: String,
        finalCards: RankCounts?,
        candidates: List<RankCountsCandidate>
    ) {
        issueSampleStore.saveEvidence(
            voted.mapNotNull { it.bitmap }, finalized.zone, reason, finalized.generation,
            SystemClock.elapsedRealtime(), finalCards, candidates
        )
    }

    /** 稳定出牌 → 单写者账本命令；evidenceId 兼作账本事件 id，重试不会重复扣牌。 */
    private fun StablePlay.toLedgerCommand(): LedgerCommand.AcceptOutsidePlay =
        LedgerCommand.AcceptOutsidePlay(cards, requireNotNull(zone.seat), UUID.randomUUID().toString())

    /** 诊断记账统一入口：入环形缓冲并轮换持久化到独立 SharedPreferences key。 */
    private fun recordDiagnostics(
        evidenceId: String?,
        zone: PlayZone,
        quantity: Int?,
        rankTotal: Int?,
        decision: String,
        reason: String? = null
    ) {
        diagnosticsLog.record(evidenceId, zone.name, zone.source.name, quantity, rankTotal, decision, reason)
        runCatching { diagnosticsStore.saveDiagnostics(diagnosticsLog.toRecentJson()) }
            .onFailure { Log.w(TAG, "DIAGNOSTICS_SAVE_FAILED", it) }
    }

    private fun reasonOf(result: DispatchResult): String? = when (result) {
        is DispatchResult.Rejected -> result.reason
        is DispatchResult.Paused -> result.reason.name
        else -> null
    }

    /** 一次出牌区采样：指纹每次都有；位图只在需要识别时才截（已交出且画面没变的采样为 null）。 */
    private data class RoiFrame(
        val bitmap: Bitmap?,
        val fingerprint: IntArray
    )

    private fun detectYoloCards(bitmap: Bitmap): YoloCards? = runCatching {
        val counts = Rank.entries.associateWith { 0 }.toMutableMap()
        val detections = requireNotNull(publicPlayYolo).detect(bitmap)
        detections.forEach { detection ->
            counts[detection.rank] = counts.getValue(detection.rank) + 1
        }
        YoloCards(RankCounts.of(counts), detections.minOfOrNull { it.confidence } ?: 0f, detections)
    }.onFailure { Log.w(TAG, "YOLO_PLAY_FAILED", it) }.getOrNull()

    private data class YoloCards(
        val cards: RankCounts,
        val minimumConfidence: Float,
        val detections: List<YoloDetection>
    )

    private fun isCardWhiteColumn(image: Image, canonicalX: Int, top: Int, bottom: Int): Boolean {
        var whitePixels = 0
        for (y in top until bottom) {
            val rgb = pixelRgb(image, canonicalX, y)
            val red = (rgb shr 16) and 0xff
            val green = (rgb shr 8) and 0xff
            val blue = rgb and 0xff
            if (red >= PLAY_WHITE_LIMIT && green >= PLAY_WHITE_LIMIT && blue >= PLAY_WHITE_LIMIT) {
                whitePixels++
                if (whitePixels >= CARD_WHITE_COLUMN_MIN_PIXELS) return true
            }
        }
        return false
    }

    /** 出牌区模型输入：按牌面白色范围自适应扩展裁剪框，整屏区域平均缩到 1280×578 画布后取这一区，等比缩放居中、整数像素偏移、四周填 114 灰（[ScreenScale.playZoneInput]）。 */
    private fun publicPlayBitmap(image: Image, zone: PlayZone): Bitmap {
        val crop = zone.expandedYoloCrop(ScreenScale.CANVAS_WIDTH, ScreenScale.CANVAS_HEIGHT) { x, top, bottom ->
            isCardWhiteColumn(image, x, top, bottom)
        }
        val pixels = ScreenScale.playZoneInput(image.toRaster(), crop.left, crop.top, crop.width, crop.height)
        return Bitmap.createBitmap(pixels, ScreenScale.MODEL_SIZE, ScreenScale.MODEL_SIZE, Bitmap.Config.ARGB_8888)
    }
    /**
     * 重置本局的识别跟踪，三个开关见 [SessionTrackingReset]：
     * 忽略桌上上一局残留的牌、牌桌第一次出现时判断是不是中途打开、本局是否已进入正式出牌阶段，是三回事，各管各的。
     */
    private fun resetSessionTracking(reset: SessionTrackingReset) {
        val generation = ++captureGeneration
        roiEventCache.reset(generation, primeExistingTable = reset.primeExistingTable)
        zoneSignals.reset()
        restartPrimeDeadlineAt = if (reset.primeExistingTable) {
            SystemClock.elapsedRealtime() + RESTART_TABLE_GRACE_MS
        } else {
            0L
        }
        resetHandProbe()
        lastHandSignature = null
        lastHandSampleAt = 0L
        stableHandSamples = 0
        maxHandChangePercent = 0.0
        lastRankProbeAt = 0L
        lastRoiSampleAt = 0L
        gameSkipped = false
        midGameOpenDetector.reset(reset.checkMidOpen)
        openingPlayGate.reset(alreadyInPlay = reset.alreadyInPlay)
        rankHandler.post {
            stablePlayGate.reset()
            handMerger.reset()
            positionVoter.reset()
            handSampleThrottle.reset()
        }
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_counter)
            .setContentTitle("记牌器截屏探针")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "截屏探针",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "card_counter_capture"
        private const val NOTIFICATION_ID = 1002
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "projection_data"
        private const val ACTION_REQUEST_NEW_GAME = "com.example.cardcounter.REQUEST_NEW_GAME"
        private const val ACTION_CONFIRM_HAND = "com.example.cardcounter.CONFIRM_HAND"
        private const val ACTION_RESUME = "com.example.cardcounter.RESUME"
        private const val EXTRA_REVERT_LAST = "revert_last"
        private const val REPORT_INTERVAL_MS = 1_000L
        private const val HAND_SAMPLE_INTERVAL_MS = 250L
        private const val STABLE_CHANGE_PERCENT = 1.5
        private const val HAND_SIGNATURE_COLUMNS = 12
        private const val HAND_SIGNATURE_ROWS = 8
        private const val RANK_PROBE_INTERVAL_MS = 250L
        private const val PLAY_WHITE_LIMIT = 190
        private const val CARD_WHITE_COLUMN_MIN_PIXELS = 8
        private const val ROI_SAMPLE_INTERVAL_MS = 50L
        private const val FINALIZE_FRAME_COUNT = 3
        private const val RESTART_TABLE_GRACE_MS = 2_000L
        private const val EXTRA_DRAFT_VERSION = "draft_version"
        private const val TAG = "CaptureProbe"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureProbeService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 开新局；截屏没在运行时什么都不做，返回 false，调用方如实告诉用户。 */
        fun requestNewGame(context: Context): Boolean {
            if (!CaptureProbeStatus.snapshot().running) return false
            ContextCompat.startForegroundService(
                context,
                Intent(context, CaptureProbeService::class.java).setAction(ACTION_REQUEST_NEW_GAME)
            )
            return true
        }

        /** 悬浮窗点“确认手牌”：带上点击时屏幕上显示的草稿版本，交给识别服务在它的线程里发确认。 */
        fun confirmHand(context: Context, draftVersion: Long) {
            if (!CaptureProbeStatus.snapshot().running) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, CaptureProbeService::class.java)
                    .setAction(ACTION_CONFIRM_HAND)
                    .putExtra(EXTRA_DRAFT_VERSION, draftVersion)
            )
        }

        /** 停记时点“继续记牌”（[revertLast] 为 false）或“撤销这一手”（true）；截屏没在运行时返回 false，由调用方直接恢复。 */
        fun resume(context: Context, revertLast: Boolean): Boolean {
            if (!CaptureProbeStatus.snapshot().running) return false
            ContextCompat.startForegroundService(
                context,
                Intent(context, CaptureProbeService::class.java)
                    .setAction(ACTION_RESUME)
                    .putExtra(EXTRA_REVERT_LAST, revertLast)
            )
            return true
        }
    }
}
