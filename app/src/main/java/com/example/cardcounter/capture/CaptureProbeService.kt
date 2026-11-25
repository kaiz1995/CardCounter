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
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.domain.SessionPhase
import com.example.cardcounter.persistence.SessionRepository
import com.example.cardcounter.persistence.SessionStore
import com.example.cardcounter.service.OverlayService
import com.example.cardcounter.storage.DiagnosticsLog
import com.example.cardcounter.storage.LedgerCommand
import com.example.cardcounter.storage.SessionActor
import com.example.cardcounter.vision.OnnxRankDetector
import com.example.cardcounter.vision.YoloDetection
import java.util.UUID
import kotlin.math.roundToInt

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
    private val turnTracker = TurnTracker()
    private val roiEventCache = RoiPlayEventCache<RoiFrame>(
        isStill = { previous, current -> RoiFingerprint.isStill(previous.fingerprint, current.fingerprint) },
        discard = { it.bitmap?.recycle() }
    )
    private var lastRoiSampleAt = 0L
    private var restartPrimeDeadlineAt = 0L
    @Volatile private var captureGeneration = 0L
    @Volatile private var handRevision = 0L
    /** 中途打开：本局不记，等下一局；新局重置时清掉。 */
    @Volatile private var gameSkipped = false
    /** 还没判断过“是不是中途打开”；牌桌第一次出现时判断一次。 */
    @Volatile private var openCheckPending = true
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
            callbackHandler.post { confirmHandFromPanel() }
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
            resetSessionTracking(primeExistingTable = resumed, checkMidOpen = !resumed)
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
                stableHandSamples = if (change != null && change <= STABLE_CHANGE_PERCENT) stableHandSamples + 1 else 0
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
                try {
                    val ink = handRankMatcher.snapshot(image)
                    val luma = handCardPositionDetector.snapshot(image)
                    val handYoloFrame = if (publicPlayYolo != null) {
                        handYoloBitmap(image)
                    } else null
                    val revision = handRevision
                    rankHandler.post {
                        try {
                            val frame = handRankMatcher.prepare(ink)
                            if (gameSkipped || revision != handRevision || sessionLedger.state.handConfirmed) return@post
                            val positionCount = handCardPositionDetector.detect(luma)
                            val templateCards = handRankMatcher.detect(frame)
                            val yoloCards = handYoloFrame?.let(::detectYoloCards)
                            val cards = chooseHandCards(positionCount, yoloCards?.cards, templateCards)
                            // 识别期间可能开了新局或手牌刚确认：旧结果不能写进新的草稿。
                            if (revision != handRevision || sessionLedger.state.handConfirmed) return@post
                            HandPanelState.draft.refresh(cards, positionCount)
                            HandPanelState.changed()
                            Log.i(
                                TAG,
                                "HAND_CANDIDATE total=${cards.total()} positionCount=$positionCount " +
                                    "yolo=$yoloCards template=$templateCards cards=$cards"
                            )
                        } finally {
                            handYoloFrame?.recycle()
                            rankProbeGate.release()
                        }
                    }
                    lastRankProbeAt = now
                } catch (error: Throwable) {
                    rankProbeGate.release()
                    throw error
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

    /** 与 [rgb] 同一套换算，直接返回 0xRRGGBB，不分配数组（圆标和指纹每 50ms 要取几千个点）。 */
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

    private fun rgb(image: Image, canonicalX: Int, canonicalY: Int): IntArray {
        val plane = image.planes[0]
        val x = canonicalX * image.width / 1_280
        val y = canonicalY * image.height / 578
        val offset = y * plane.rowStride + x * plane.pixelStride
        return IntArray(3) { plane.buffer.get(offset + it).toInt() and 0xff }
    }

    /**
     * 手动新局：只重置，不锁手牌。账本开新局、手牌草稿清空，手牌识别接着在后台跑，等用户在悬浮窗上确认。
     * 新局不当作中途打开：这是用户主动开的。桌上已有的牌（上一局残留）当作旧牌不记，
     * 每个出牌区清空一次之后才开始记新出的牌。
     */
    private fun startNewGame() {
        sessionActor.post(LedgerCommand.NewSession())
        resetSessionTracking(primeExistingTable = true, checkMidOpen = false)
        OverlayService.refresh(this)
        Log.i(TAG, "MANUAL_NEW_GAME")
    }

    /** 用户在悬浮窗上点“确认手牌”：用当时显示的点数和边缘张数发一次确认，结果回显在面板上。 */
    private fun confirmHandFromPanel() {
        val data = HandPanelState.draft.snapshot().confirmData() ?: return
        val result = sessionActor.post(LedgerCommand.ConfirmHand(data.cards, data.handCount))
        HandPanelState.rejectReason = (result as? DispatchResult.Rejected)?.reason
        HandPanelState.changed()
        OverlayService.refresh(this)
        Log.i(
            TAG,
            "SELF_HAND_CONFIRM total=${data.cards.total()} positionCount=${data.handCount} " +
                "cards=${data.cards} result=${result.javaClass.simpleName}"
        )
    }

    /** 截屏回调仅缓存小 ROI；牌面连续停稳后才推理，清桌是未停稳的兜底。 */
    private fun captureRoiEvents(image: Image) {
        if (gameSkipped) return
        val tableVisible = gameTableVisible(image)
        val visibleZones = if (tableVisible) playZoneVisibility(image) else emptyMap()
        if (tableVisible && openCheckPending) {
            openCheckPending = false
            val ledger = sessionLedger.state
            if (shouldSkipOpenedGame(
                    ledger.handConfirmed,
                    ledger.outsidePlayedCount,
                    visibleZones.values.count { it }
                )
            ) {
                gameSkipped = true
                HandPanelState.skipped = true
                HandPanelState.changed()
                OverlayService.refresh(this)
                Log.i(TAG, "OPENED_MID_GAME_SKIP visibleZones=${visibleZones.filterValues { it }.keys}")
                return
            }
        }
        val generation = captureGeneration
        val now = SystemClock.elapsedRealtime()
        val takeSample = now - lastRoiSampleAt >= ROI_SAMPLE_INTERVAL_MS
        if (!tableVisible && restartPrimeDeadlineAt > 0L &&
            SystemClock.elapsedRealtime() >= restartPrimeDeadlineAt
        ) {
            roiEventCache.releasePrime(generation)
            restartPrimeDeadlineAt = 0L
            Log.i(TAG, "PLAY_GATE_PRIME_RELEASED_OUTSIDE_TABLE")
        }
        // 圆标、指纹、截图、清桌计数全按同一个采样节拍推进（与电脑回放“每条观察就是一次采样”一致）；
        // 非采样帧不能推进清桌计数，否则一帧闪空就会打断停稳。
        if (!takeSample) return
        lastRoiSampleAt = now
        PlayZone.entries.forEach { zone ->
            val visible = tableVisible && isPlayVisible(visibleZones[zone] == true)
            // “出牌”圆标：这一区牌已收走（且没有待兜底交出的牌）、圆标连续两次采样都亮才算轮到他；
            // 之后再出一样的牌才算新的一手。兜底交出的入账先 post，所以一定排在 noteTurn 前面。
            if (tableVisible && zone.source == PlaySource.OUTSIDE) {
                val shown = TurnBadge.isShown(zone) { x, y -> pixelRgb(image, x, y) }
                val zoneEmpty = !visible && !roiEventCache.hasUndelivered(zone, generation)
                if (turnTracker.update(zone, shown, zoneEmpty = zoneEmpty)) {
                    Log.i(TAG, "TURN_BADGE zone=$zone")
                    rankHandler.post { stablePlayGate.noteTurn(zone) }
                }
            }
            val frame = if (visible && roiEventCache.needsFrame(zone, generation)) {
                val probe = RoiFrame(null, RoiFingerprint.of(zone) { x, y -> pixelRgb(image, x, y) })
                // 已交出且画面没变：只要指纹，不再截 320 图送识别；画面一变，这一帧是下一段静止的起点，要带图。
                if (roiEventCache.isRepeat(zone, generation, probe)) probe else probe.copy(bitmap = publicPlayBitmap(image, zone))
            } else {
                null
            }
            roiEventCache.observe(zone, tableVisible, visible, frame, generation)?.let { finalized ->
                Log.i(TAG, "ROI_FINALIZED zone=${finalized.zone} frames=${finalized.frames.size}")
                rankHandler.post { finalizeRoiEvent(finalized) }
            }
        }
    }

    private fun finalizeRoiEvent(finalized: RoiPlayEventCache.Finalized<RoiFrame>) {
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
            val winner = candidates
                .filter { it.cards.total() > 0 }
                .groupBy { it.cards }
                .maxWithOrNull(
                    compareBy<Map.Entry<RankCounts, List<RankCountsCandidate>>> { it.value.size }
                        .thenBy { entry -> entry.value.minOf { it.minimumConfidence } }
                )
            val cards = winner?.let { entry ->
                entry.key.takeIf {
                    entry.value.size >= 2 || canFastConfirmPlay(it, entry.value.first().minimumConfidence)
                }
            }
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
            if (sessionLedger.state.phase != SessionPhase.ACTIVE) return
            val stable = stablePlayGate.accept(finalized.zone, cards)
            if (stable == null) {
                Log.i(TAG, "PLAY_DUPLICATE_SUPPRESSED zone=${finalized.zone} cards=$cards")
                return
            }
            recordDiagnostics(
                null, finalized.zone,
                quantity = stable.cards.total(),
                rankTotal = stable.cards.total(),
                decision = DiagnosticsLog.Decision.STABLE
            )
            // 单写者派发：evidenceId 兼作账本事件 id，重试不会重复扣牌。
            val command = stable.toLedgerCommand()
            val result = sessionActor.post(command)
            recordDiagnostics(
                command.evidenceId, finalized.zone,
                quantity = stable.cards.total(),
                rankTotal = stable.cards.total(),
                decision = DiagnosticsLog.Decision.forResult(result),
                reason = reasonOf(result)
            )
            when (result) {
                is DispatchResult.Rejected -> {
                    saveEvidence(finalized, voted, "REDUCER_REJECTED", cards, candidates)
                    OverlayService.refresh(this)
                    Log.w(TAG, "PLAY_REJECTED zone=${finalized.zone} reason=${result.reason}")
                }
                is DispatchResult.Paused -> {
                    saveEvidence(finalized, voted, "PAUSED_${result.reason.name}", cards, candidates)
                    OverlayService.refresh(this)
                    Log.w(TAG, "PLAY_PAUSED zone=${finalized.zone} reason=${result.reason}")
                }
                else -> {
                    OverlayService.refresh(this)
                    Log.i(
                        TAG,
                        "FORMAL_PLAY source=${finalized.zone.source} zone=${finalized.zone} " +
                            "cards=$cards decision=${DiagnosticsLog.Decision.forResult(result)}"
                    )
                }
            }
        } finally {
            finalized.frames.forEach { it.bitmap?.recycle() }
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

    private fun publicPlayBitmap(image: Image, zone: PlayZone): Bitmap {
        val crop = zone.yoloCrop(1_280, 578)
        val left = crop.left
        val top = crop.top
        val width = crop.width
        val height = crop.height
        val plane = image.planes[0]
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val sourceX = (left + x) * image.width / 1_280
            val sourceY = (top + y) * image.height / 578
            val offset = sourceY * plane.rowStride + sourceX * plane.pixelStride
            val red = plane.buffer.get(offset).toInt() and 0xff
            val green = plane.buffer.get(offset + 1).toInt() and 0xff
            val blue = plane.buffer.get(offset + 2).toInt() and 0xff
            pixels[y * width + x] = 0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
        }
        val source = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val scale = minOf(320f / width, 320f / height)
        val scaled = Bitmap.createScaledBitmap(
            source,
            (width * scale).roundToInt(),
            (height * scale).roundToInt(),
            true
        )
        val output = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        output.eraseColor(0xff727272.toInt())
        val canvas = android.graphics.Canvas(output)
        canvas.drawBitmap(scaled, (320 - scaled.width) / 2f, (320 - scaled.height) / 2f, null)
        if (scaled !== source) source.recycle()
        scaled.recycle()
        return output
    }

    private fun handYoloBitmap(image: Image): Bitmap {
        val width = HAND_YOLO_RIGHT - HAND_YOLO_LEFT
        val height = HAND_YOLO_BOTTOM - HAND_YOLO_TOP
        val pixels = IntArray(width * width)
        val plane = image.planes[0]
        for (y in 0 until height) for (x in 0 until width) {
            val sourceX = (HAND_YOLO_LEFT + x) * image.width / 1_280
            val sourceY = (HAND_YOLO_TOP + y) * image.height / 578
            val offset = sourceY * plane.rowStride + sourceX * plane.pixelStride
            val red = plane.buffer.get(offset).toInt() and 0xff
            val green = plane.buffer.get(offset + 1).toInt() and 0xff
            val blue = plane.buffer.get(offset + 2).toInt() and 0xff
            pixels[(HAND_YOLO_PAD_TOP + y) * width + x] =
                0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
        }
        return Bitmap.createBitmap(pixels, width, width, Bitmap.Config.ARGB_8888)
    }

    private fun playZoneVisibility(image: Image): Map<PlayZone, Boolean> = PlayZone.entries.associateWith { zone ->
        val left = zone.left(1_280)
        val right = zone.right(1_280)
        val top = zone.rankY(578)
        var white = 0
        var samples = 0
        for (y in top..(top + zone.visibilityHeight) step PLAY_VISIBILITY_STEP) {
            for (x in left..right step PLAY_VISIBILITY_STEP) {
                if (rgb(image, x, y).all { it >= PLAY_WHITE_LIMIT }) white++
                samples++
            }
        }
        hasVisibleCardArea(white, samples)
    }

    private fun gameTableVisible(image: Image): Boolean =
        listOf(500 to 200, 800 to 200).all { (x, y) ->
            val color = rgb(image, x, y)
            isTableGreen(color[0], color[1], color[2])
        }

    /**
     * 重置本局的识别跟踪。
     * [primeExistingTable]：接着上次的这一局记，桌上已有的牌当作旧牌，不重复记。
     * [checkMidOpen]：牌桌第一次出现时判断是不是中途打开（桌上已有出牌而账本是空的）。
     */
    private fun resetSessionTracking(primeExistingTable: Boolean, checkMidOpen: Boolean) {
        val generation = ++captureGeneration
        roiEventCache.reset(generation, primeExistingTable = primeExistingTable)
        turnTracker.reset()
        restartPrimeDeadlineAt = if (primeExistingTable) {
            SystemClock.elapsedRealtime() + RESTART_TABLE_GRACE_MS
        } else {
            0L
        }
        handRevision += 1
        lastHandSignature = null
        lastHandSampleAt = 0L
        stableHandSamples = 0
        maxHandChangePercent = 0.0
        lastRankProbeAt = 0L
        lastRoiSampleAt = 0L
        gameSkipped = false
        openCheckPending = checkMidOpen
        HandPanelState.reset()
        rankHandler.post { stablePlayGate.reset() }
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
        private const val REPORT_INTERVAL_MS = 1_000L
        private const val HAND_SAMPLE_INTERVAL_MS = 250L
        private const val STABLE_CHANGE_PERCENT = 1.5
        private const val HAND_SIGNATURE_COLUMNS = 12
        private const val HAND_SIGNATURE_ROWS = 8
        private const val RANK_PROBE_INTERVAL_MS = 250L
        private const val PLAY_VISIBILITY_STEP = 8
        private const val PLAY_WHITE_LIMIT = 190
        private const val ROI_SAMPLE_INTERVAL_MS = 50L
        private const val FINALIZE_FRAME_COUNT = 3
        private const val RESTART_TABLE_GRACE_MS = 2_000L
        private const val HAND_YOLO_LEFT = HandRegions.YOLO_LEFT
        private const val HAND_YOLO_RIGHT = HandRegions.YOLO_RIGHT
        private const val HAND_YOLO_TOP = HandRegions.YOLO_TOP
        private const val HAND_YOLO_BOTTOM = HandRegions.YOLO_BOTTOM
        private const val HAND_YOLO_PAD_TOP = 540
        private const val TAG = "CaptureProbe"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureProbeService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun requestNewGame(context: Context) {
            if (!CaptureProbeStatus.snapshot().running) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, CaptureProbeService::class.java).setAction(ACTION_REQUEST_NEW_GAME)
            )
        }

        /** 悬浮窗点“确认手牌”：交给识别服务在它的线程里发确认。 */
        fun confirmHand(context: Context) {
            if (!CaptureProbeStatus.snapshot().running) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, CaptureProbeService::class.java).setAction(ACTION_CONFIRM_HAND)
            )
        }
    }
}
