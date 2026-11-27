package com.example.cardcounter.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
import com.example.cardcounter.domain.HandAutoLock
import com.example.cardcounter.domain.HandConfirmData
import com.example.cardcounter.domain.HandLockGuard
import com.example.cardcounter.domain.HandReading
import com.example.cardcounter.domain.PauseReason
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
import com.example.cardcounter.vision.Raster
import com.example.cardcounter.vision.ScreenScale
import com.example.cardcounter.vision.YoloDetection
import java.util.UUID

/** MediaProjection 采集服务：取帧、手牌锁定、六区出牌识别并经 SessionActor 入账。W5 会把识别与状态机拆进 :core。 */
class CaptureProbeService : Service() {
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    /** 上一次写进日志的牌桌可见性；只在翻转时记一条 TABLE_VISIBLE_CHANGED。 */
    private var lastLoggedTableVisible: Boolean? = null
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
    private val gateCoordinator = RankGateCoordinator { task ->
        if (::rankHandler.isInitialized) rankHandler.post(task) else false
    }
    private val stablePlayGate: StablePlayGate get() = gateCoordinator.stablePlayGate
    private val zoneSignals = ZoneSignalTracker()
    private val roiEventCache = RoiPlayEventCache<RoiFrame>(
        isStill = { previous, current -> RoiFingerprint.isStill(previous.fingerprint, current.fingerprint) },
        discard = { it.bitmap?.recycle() }
    )
    private var lastRoiSampleAt = 0L
    private var restartPrimeDeadlineAt = 0L
    private val captureGeneration: Long get() = gateCoordinator.generation
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
    /** 手牌自动锁定：识别结果只喂给它；开局门因圆标打开的那一刻锁定，之后不再接收（见 [lockHand]）。 */
    private val handAutoLock = HandAutoLock()
    /** 本局手牌已锁定（或接着上次记、不再认手牌）：不再做手牌识别。新局清掉。 */
    @Volatile private var handLocked = false
    /** 当前是否横屏且牌桌可见：手牌识别只在这时跑，竖屏、大厅、结算画面不识别。 */
    @Volatile private var handProbeOnTable = false
    /** 最近一次 50ms 采样时自己出牌区（SELF）是否摆着牌，用于区分“还贡变少”与“自己首出变少”。 */
    @Volatile private var selfCardOnTable = false
    /** 上一帧是否处于横屏：用于仅在由横转竖那一刻重置出牌区信号，避免把竖屏时长算成空桌。 */
    private var wasLandscape = false
    /** 中途打开：本局不记，等下一局；新局重置时清掉。 */
    @Volatile private var gameSkipped = false
    /** 中途打开连续多帧判定器：仅统计 OUTSIDE 区，连续 5 次采样可见才判中途打开。 */
    private val midGameOpenDetector = MidGameOpenDetector()
    /** 开局换牌期门控：由 [gateCoordinator] 统一在识别线程重置与推进，避免跨线程竞态。 */
    private val openingPlayGate: OpeningPlayGate get() = gateCoordinator.openingPlayGate
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
        if (projection == null) startCapture(intent)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        display?.release()
        reader?.close()
        // 先摘掉回调：自己停投屏不能被当成“系统停止”而进入暂停态。
        projectionCallback?.let { runCatching { projection?.unregisterCallback(it) } }
        projectionCallback = null
        projection?.stop()
        display = null
        reader = null
        projection = null
        // 先在锁里标记退出、推进代数并清掉手牌截图：之后识别线程不再写回，确认手牌也不再存证据。
        synchronized(this) {
            destroyed = true
            replaceHandProbe(null)
            roiEventCache.reset(gateCoordinator.nextGeneration(), primeExistingTable = false)
        }
        publicPlayYolo?.close()
        publicPlayYolo = null
        issueSampleStore.close()
        sessionActor.shutdown()
        OverlayProbeState.reset()
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
            OverlayService.refresh(this)
            lastLoggedTableVisible = null
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            projection = requireNotNull(manager.getMediaProjection(resultCode, data)) {
                "MediaProjection 不可用"
            }
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay.getRealMetrics(metrics)
            val rawWidth = metrics.widthPixels.coerceAtLeast(1)
            val rawHeight = metrics.heightPixels.coerceAtLeast(1)
            // 截屏缓冲按横屏固定：即使授权回调时在竖屏，切横屏后图像宽高仍满足 width > height。
            val width = maxOf(rawWidth, rawHeight)
            val height = minOf(rawWidth, rawHeight)
            wasLandscape = false
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
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (destroyed) return
                    Log.w(TAG, "CAPTURE_STOPPED_BY_SYSTEM")
                    // 用户从系统下拉栏停止投屏后已经没法记牌：采集服务收掉，悬浮窗留着并进入“已暂停”，
                    // 用户点“继续记牌”就能在游戏里重新授权，不用回主界面。
                    CaptureProbeStatus.systemStopped()
                    OverlayService.refresh(this@CaptureProbeService)
                    stopSelf()
                }
            }
            projectionCallback = callback
            projection!!.registerCallback(callback, callbackHandler)
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
            val isLandscape = image.width > image.height &&
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (!isLandscape) {
                // 刚由横转竖的那一刻清空出牌区信号（清桌计时与圆标计数），避免竖屏期间被算成空桌误清桌。
                if (wasLandscape) {
                    wasLandscape = false
                    zoneSignals.reset()
                }
                // 竖屏时手牌识别和出牌识别都不做。
                if (handProbeOnTable) {
                    handProbeOnTable = false
                    stableHandSamples = 0
                    lastHandSignature = null
                    OverlayProbeState.update { it.copy(tableVisible = false) }
                }
            } else {
                wasLandscape = true
                captureRoiEvents(image)
                if (!handLocked && handProbeOnTable && now - lastHandSampleAt >= HAND_SAMPLE_INTERVAL_MS) {
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
                if (!gameSkipped && shouldProbeHand(handLocked, handProbeOnTable, stableHandSamples) &&
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
                        val generation = captureGeneration
                        val revision = handRevision
                        val scene = handScene
                        val selfCardVisible = handProbeOnTable && selfCardOnTable
                        posted = rankHandler.post {
                            var handAreaKept = false
                            try {
                                if (generation != captureGeneration || gameSkipped || revision != handRevision || handLocked || !handProbeOnTable) return@post
                                val rowCounts = handCardPositionDetector.rowCounts(luma)
                                val validRows = handCardPositionDetector.validRowCounts(rowCounts)
                                val positionCount = validRows?.let { it.first + it.second }
                                val rawYoloCards = yoloFrame?.let(::detectYoloCards)
                                if (generation != captureGeneration || gameSkipped || revision != handRevision || handLocked || !handProbeOnTable) return@post
                                val yoloCards = rawYoloCards?.let { cardsResult ->
                                    val kept = HandLockGuard.filterHandDetections(cardsResult.detections)
                                    if (kept.size == cardsResult.detections.size) cardsResult
                                    else {
                                        val counts = Rank.entries.associateWith { r -> kept.count { it.rank == r } }
                                        cardsResult.copy(
                                            cards = RankCounts.of(counts),
                                            minimumConfidence = kept.minOfOrNull { it.confidence } ?: 0f,
                                            detections = kept
                                        )
                                    }
                                }
                                // 模型已加载但这一帧出错或 0 个框：本帧不进合并、不刷新结果，也不拿模板顶替。
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
                                val votedRows = positionVoter.add(scene, validRows)
                                val votedPosition = votedRows?.let { it.first + it.second }
                                val reading = if (merge != null) {
                                    HandLockGuard.buildReading(cards, merge.slotDetections, votedRows)
                                } else {
                                    HandReading(cards, votedPosition, structureValid = false)
                                }
                                handAreaKept = publishHandProbe(
                                    revision, reading,
                                    HandProbe(area, rowCounts, merge?.merged, merge?.slotDetections.orEmpty(), templateCards),
                                    selfCardVisible
                                )
                                if (!handAreaKept) return@post
                                Log.i(
                                    TAG,
                                    "HAND_CANDIDATE total=${cards.total()} single=${merge?.single?.total()} " +
                                        "merged=${merge?.merged?.total()} frames=${merge?.frames} " +
                                        "positionCount=$positionCount voted=$votedPosition rows=${rowCounts.first}/${rowCounts.second} " +
                                        "structureValid=${reading.structureValid} yolo=$yoloCards template=$templateCards cards=$cards"
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
     * 手动新局：只重置，不锁手牌。账本开新局、手牌自动锁定状态清空，切回出牌前门控。
     * 新局不当作中途打开：这是用户主动开的。桌上已有的牌（上一局残留）当作旧牌不记，
     * 每个出牌区清空一次之后才开始记新出的牌。
     */
    private fun startNewGame() {
        synchronized(this) {
            sessionActor.post(LedgerCommand.NewSession())
            resetSessionTracking(SessionTrackingReset.onManualNewGame())
        }
        OverlayService.refresh(this)
        Log.i(TAG, "MANUAL_NEW_GAME")
    }

    /**
     * 开局门因圆标打开的那一刻调用：从 [handAutoLock] 取最近一段合格手牌并锁进账本。
     * 返回 null 时以 [PauseReason.HAND_NOT_RECOGNIZED] 停记并存证据包；之后不再接收手牌识别结果。
     */
    private fun lockHand(expectedGeneration: Long) {
        val (data, latestBeforeReset, frozenProbe, areaCopy) = synchronized(this) {
            if (destroyed ||
                handLocked ||
                expectedGeneration != captureGeneration ||
                sessionLedger.state.phase != SessionPhase.ACTIVE ||
                sessionLedger.state.handConfirmed
            ) {
                return
            }
            handLocked = true
            val lockedData = handAutoLock.lock()
            val latestReading = if (lockedData == null) handAutoLock.latest() else null
            val probe = handProbe
            val copy = probe?.area?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
            replaceHandProbe(null)
            LockSnapshot(lockedData, latestReading, probe, copy)
        }
        try {
            handMerger.reset()
            positionVoter.reset()
            handSampleThrottle.reset()
            if (data == null) {
                val pauseResult = synchronized(this) {
                    if (destroyed || expectedGeneration != captureGeneration) return
                    val result = sessionActor.post(LedgerCommand.Pause(PauseReason.HAND_NOT_RECOGNIZED))
                    OverlayProbeState.update {
                        it.copy(handLocked = true, handConfirmedAtMs = null, handConfirmedCount = null)
                    }
                    result
                }
                val metadata = handAutoLockEvidenceJson(
                    reason = PauseReason.HAND_NOT_RECOGNIZED.name,
                    timeMs = SystemClock.elapsedRealtime(),
                    latest = latestBeforeReset,
                    topRowCount = frozenProbe?.rows?.first,
                    bottomRowCount = frozenProbe?.rows?.second,
                    model = frozenProbe?.model,
                    modelDetections = frozenProbe?.modelDetections.orEmpty(),
                    template = frozenProbe?.template,
                    outcome = outcomeOf(pauseResult)
                )
                issueSampleStore.saveHandNotRecognized(areaCopy, metadata)
                OverlayService.refresh(this)
                Log.w(
                    TAG,
                    "SELF_HAND_LOCK_FAILED reason=HAND_NOT_RECOGNIZED latest=$latestBeforeReset result=${outcomeOf(pauseResult)}"
                )
                return
            }
            val (confirmResult, finalResult) = synchronized(this) {
                if (destroyed || expectedGeneration != captureGeneration) return
                val result = sessionActor.post(LedgerCommand.ConfirmHand(data.cards, data.handCount))
                if (result is DispatchResult.Accepted) {
                    val nowMs = SystemClock.elapsedRealtime()
                    OverlayProbeState.update {
                        it.copy(
                            handLocked = true,
                            handConfirmedAtMs = nowMs,
                            handConfirmedCount = data.cards.total()
                        )
                    }
                    result to result
                } else {
                    val afterPause = if (
                        result is DispatchResult.Rejected &&
                        result.state.phase == SessionPhase.ACTIVE &&
                        !result.state.handConfirmed
                    ) {
                        sessionActor.post(LedgerCommand.Pause(PauseReason.HAND_NOT_RECOGNIZED))
                    } else {
                        result
                    }
                    OverlayProbeState.update {
                        it.copy(handLocked = true, handConfirmedAtMs = null, handConfirmedCount = null)
                    }
                    result to afterPause
                }
            }
            if (confirmResult !is DispatchResult.Accepted) {
                val metadata = handAutoLockEvidenceJson(
                    reason = HAND_CONFIRM_FAILED,
                    timeMs = SystemClock.elapsedRealtime(),
                    latest = HandReading(data.cards, data.handCount, structureValid = true),
                    topRowCount = frozenProbe?.rows?.first,
                    bottomRowCount = frozenProbe?.rows?.second,
                    model = frozenProbe?.model,
                    modelDetections = frozenProbe?.modelDetections.orEmpty(),
                    template = frozenProbe?.template,
                    outcome = outcomeOf(confirmResult)
                )
                issueSampleStore.saveHandNotRecognized(areaCopy, metadata)
                Log.w(
                    TAG,
                    "SELF_HAND_CONFIRM_REJECTED confirm=${outcomeOf(confirmResult)} final=${outcomeOf(finalResult)}"
                )
            }
            OverlayService.refresh(this)
            Log.i(
                TAG,
                "SELF_HAND_LOCKED total=${data.cards.total()} positionCount=${data.handCount} " +
                    "cards=${data.cards} result=${outcomeOf(confirmResult)} final=${outcomeOf(finalResult)}"
            )
        } finally {
            areaCopy?.recycle()
        }
    }

    private data class LockSnapshot(
        val data: HandConfirmData?,
        val latest: HandReading?,
        val probe: HandProbe?,
        val areaCopy: Bitmap?
    )

    private fun outcomeOf(result: DispatchResult): String = when (result) {
        is DispatchResult.Accepted -> "ACCEPTED"
        is DispatchResult.DuplicateIgnored -> "DUPLICATE_IGNORED"
        is DispatchResult.Rejected -> "REJECTED:${result.reason}"
        is DispatchResult.Paused -> "PAUSED:${result.reason.name}"
    }

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
     * 识别线程写回这一手的结果：检查“还是这一局、还没锁定、服务没退出”并喂给 [handAutoLock]；
     * 新局重置也拿这把锁，上一局的结果就写不进新局。写回了返回 true（截图归 handProbe 管）。
     */
    @Synchronized
    private fun publishHandProbe(revision: Long, reading: HandReading, probe: HandProbe, selfCardVisible: Boolean): Boolean {
        if (destroyed || revision != handRevision || handLocked || sessionLedger.state.handConfirmed) return false
        handAutoLock.observe(reading, selfCardVisible)
        replaceHandProbe(probe)
        val latestCards = handAutoLock.latest()?.cards
        OverlayProbeState.update { it.copy(latestHandCards = latestCards) }
        return true
    }

    /** 新局：作废识别线程手里的旧结果，清掉上一局的手牌截图与自动锁定状态。 */
    @Synchronized
    private fun resetHandProbe(alreadyLocked: Boolean) {
        handRevision += 1
        handLocked = alreadyLocked
        selfCardOnTable = false
        handAutoLock.reset()
        // 上一局的手牌截图不能留给新局当证据，也别一直占着内存。
        replaceHandProbe(null)
        OverlayProbeState.update {
            OverlayProbeSnapshot(
                tableVisible = handProbeOnTable,
                handLocked = alreadyLocked
            )
        }
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

    /** tableVisible 翻转时记一条（不逐帧记）：命中数和每个探针点的 RGB，“+”判绿、“-”不绿。 */
    private fun logTableVisibleFlip(raster: Raster, tableVisible: Boolean) {
        if (lastLoggedTableVisible == tableVisible) return
        lastLoggedTableVisible = tableVisible
        val readings = tableProbeReadings(raster)
        Log.i(
            TAG,
            "TABLE_VISIBLE_CHANGED visible=$tableVisible hits=${readings.count { it.green }}/${readings.size} " +
                "need=$TABLE_GREEN_MIN_HITS probes=${readings.joinToString(" ") { it.describe() }}"
        )
    }

    /**
     * 截屏回调仅缓存小 ROI；牌面连续停稳后才推理。没停稳的一手在被新牌直接盖上、牌桌被挡住、
     * 清桌时也会交出推理（交出规则见 RoiPlayEventCache）。
     */
    private fun captureRoiEvents(image: Image) {
        val raster = image.toRaster()
        val tableVisible = isTableVisible(raster)
        logTableVisibleFlip(raster, tableVisible)
        handProbeOnTable = tableVisible
        if (!tableVisible) {
            stableHandSamples = 0
            lastHandSignature = null
            selfCardOnTable = false
        }
        OverlayProbeState.update { it.copy(tableVisible = tableVisible) }
        if (gameSkipped) return
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
        selfCardOnTable = tableVisible && zoneStates[PlayZone.SELF] == ZoneVisibility.CARD
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
                OverlayProbeState.update { it.copy(gameSkipped = true) }
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
                    gateCoordinator.postZoneSignals(
                        expectedGeneration = generation,
                        zone = zone,
                        signals = signals,
                        nowMs = now,
                        isOnTable = { it in onTable },
                        onOpened = { lockHand(generation) },
                        onReplay = { retries -> replaySkippedPlays(retries, generation, now) }
                    )
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
                frame.bitmap?.let { detectYoloCards(it, refineOutsideJoker = true) }?.let {
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
            if (finalized.generation != captureGeneration || gameSkipped) return
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
            gateCoordinator.handleFinalizedPlay(
                expectedGeneration = finalized.generation,
                zone = finalized.zone,
                cards = cards,
                sampleTimeMs = sampleTimeMs,
                onPrePlaySkipped = {
                    // 记下这一手：开局门稍后因圆标打开时，桌上还摆着的会补记。
                    saveEvidence(finalized, voted, "PRE_PLAY_SKIPPED", cards, candidates)
                    recordDiagnostics(
                        null, finalized.zone,
                        quantity = cards.total(),
                        rankTotal = cards.total(),
                        decision = DiagnosticsLog.Decision.RETRY,
                        reason = "PRE_PLAY_SKIPPED"
                    )
                    Log.i(TAG, "PRE_PLAY_SKIPPED zone=${finalized.zone} cards=$cards")
                },
                onAdmit = {
                    admitPlay(
                        finalized.zone, cards, voteOutcome.mergedFromDisagreement, sampleTimeMs,
                        expectedGeneration = finalized.generation,
                        finalizeReason = finalized.reason,
                        fingerprint = finalized.frames.lastOrNull()?.fingerprint
                    ) { reason ->
                        saveEvidence(finalized, voted, reason, cards, candidates)
                    }
                }
            )
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
        expectedGeneration: Long,
        finalizeReason: FinalizeReason? = null,
        fingerprint: IntArray? = null,
        evidence: (String) -> Unit
    ) {
        if (expectedGeneration != captureGeneration || gameSkipped) return
        val stable = when (val gateDecision = stablePlayGate.check(zone, cards, sampleTimeMs, fingerprint)) {
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
            reason = finalizeReason?.name
        )
        // 单写者派发：evidenceId 兼作账本事件 id，重试不会重复扣牌。
        val command = stable.toLedgerCommand()
        val result = synchronized(this) {
            if (destroyed || expectedGeneration != captureGeneration || gameSkipped) return
            sessionActor.post(command)
        }
        recordDiagnostics(
            command.evidenceId, zone,
            quantity = stable.cards.total(),
            rankTotal = stable.cards.total(),
            decision = DiagnosticsLog.Decision.forResult(result),
            reason = reasonOf(result)
        )
        when (result) {
            is DispatchResult.Accepted -> {
                if (expectedGeneration != captureGeneration) return
                stablePlayGate.commitAccepted(zone, stable.cards, sampleTimeMs, fingerprint, finalizeReason) {
                    roiEventCache.isCleared(it, expectedGeneration)
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
    private fun replaySkippedPlays(
        plays: List<OpeningPlayGate.SkippedPlay>,
        expectedGeneration: Long,
        sampleTimeMs: Long
    ) {
        for (i in plays.indices) {
            if (expectedGeneration != captureGeneration || gameSkipped) return
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
            admitPlay(
                play.zone, play.cards, merged = false, sampleTimeMs = sampleTimeMs,
                expectedGeneration = expectedGeneration
            ) { }
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

    private fun detectYoloCards(bitmap: Bitmap, refineOutsideJoker: Boolean = false): YoloCards? = runCatching {
        val counts = Rank.entries.associateWith { 0 }.toMutableMap()
        val detections = requireNotNull(publicPlayYolo).detect(bitmap, refineOutsideJoker)
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
        val generation = synchronized(this) {
            val next = gateCoordinator.nextGeneration()
            roiEventCache.reset(next, primeExistingTable = reset.primeExistingTable)
            zoneSignals.reset()
            restartPrimeDeadlineAt = if (reset.primeExistingTable) {
                SystemClock.elapsedRealtime() + RESTART_TABLE_GRACE_MS
            } else {
                0L
            }
            val ledgerState = sessionLedger.state
            val alreadyLocked = isHandAlreadyLocked(reset, ledgerState.handConfirmed, ledgerState.phase)
            resetHandProbe(alreadyLocked)
            lastHandSignature = null
            lastHandSampleAt = 0L
            stableHandSamples = 0
            maxHandChangePercent = 0.0
            lastRankProbeAt = 0L
            lastRoiSampleAt = 0L
            gameSkipped = false
            midGameOpenDetector.reset(reset.checkMidOpen)
            next
        }
        gateCoordinator.postReset(generation, reset.alreadyInPlay) {
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
    }
}
