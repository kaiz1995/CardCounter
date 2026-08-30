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
import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.PauseReason
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.domain.SessionPhase
import com.example.cardcounter.persistence.SessionRepository
import com.example.cardcounter.service.OverlayService
import com.example.cardcounter.vision.MnnYoloDetector
import kotlin.math.roundToInt

/** 仅验证 MediaProjection 与帧流，不在此处实现视觉识别。 */
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
    private var publicPlayYolo: MnnYoloDetector? = null
    private var lastRankProbeAt = 0L
    private var frameCount = 0L
    private var firstFrameAt = 0L
    private var lastReportAt = 0L
    private var lastReportFrames = 0L
    private var lastHandSignature: IntArray? = null
    private var lastHandSampleAt = 0L
    private var stableHandSamples = 0
    private var maxHandChangePercent = 0.0
    private var lastRankCandidate: RankCounts? = null
    private var stableRankCandidates = 0
    private var rankRevision = -1L
    private val rankProbeGate = SingleFlightGate()
    private val stablePlayGate = StablePlayGate()
    private val publicPlayCountAggregator = PublicPlayCountAggregator()
    private val unknownPlaySamples = PlayZone.entries.associateWith { 0 }.toMutableMap()
    private val lastPlayCandidates = PlayZone.entries.associateWith<PlayZone, RankCounts?> { null }.toMutableMap()
    private val roiEventCache = RoiPlayEventCache<RoiFrame>(discard = { it.bitmap.recycle() })
    private var lastRoiSampleAt = 0L
    private var restartPrimeDeadlineAt = 0L
    @Volatile private var captureGeneration = 0L
    @Volatile private var handRevision = 0L
    @Volatile private var firstPlayArmed = false
    @Volatile private var lastStablePrePlayHand: RankCounts? = null
    @Volatile private var lastStablePositionCount: Int = 0
    @Volatile private var lastPlayRecordedAt = 0L
    @Volatile private var primeSamplesRemaining = 0
    @Volatile private var lastOutsideFinalized = emptyMap<PlayZone, Pair<RankCounts, Long>>()
    private val firstPlayGate = FirstPlayBoundaryGate()

    override fun onCreate() {
        super.onCreate()
        handRankMatcher = HandRankMatcher(this)
        handCardPositionDetector = HandCardPositionDetector()
        issueSampleStore = IssueSampleStore(this)
        publicPlayYolo = runCatching { MnnYoloDetector(this) }
            .onFailure { Log.w(TAG, "YOLO_DISABLED", it) }
            .getOrNull()
        callbackThread.start()
        rankThread.start()
        callbackHandler = Handler(callbackThread.looper)
        rankHandler = Handler(rankThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (intent?.action == ACTION_RESET_SESSION) {
            callbackHandler.post {
                resetSessionTracking(firstPlayIsArmed = false)
                Log.i(TAG, "SESSION_TRACKING_RESET")
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RELOCK_HAND) {
            callbackHandler.post {
                resetSessionTracking(firstPlayIsArmed = false)
                Log.i(TAG, "HAND_RELOCK_REQUESTED")
            }
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
            val activeSession = SessionRepository(this).state.phase == SessionPhase.ACTIVE
            resetSessionTracking(firstPlayIsArmed = activeSession)
            primeSamplesRemaining = if (activeSession) RESTART_PRIME_SAMPLES else 0
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
                val signals = openingSignals(image)
                val handChanged = change == null || change > STABLE_CHANGE_PERCENT
                if (shouldInvalidatePrePlayHand(
                        firstPlayArmed,
                        lastStablePrePlayHand != null,
                        handChanged
                    )
                ) {
                    handRevision += 1
                    lastStablePrePlayHand = null
                }
                maxHandChangePercent = maxOf(maxHandChangePercent, change ?: 0.0)
                CaptureProbeStatus.handCandidate(change, maxHandChangePercent, stableHandSamples)
                lastHandSignature = signature
                lastHandSampleAt = now

                when (firstPlayGate.update(signals, lastStablePrePlayHand != null)) {
                    FirstPlayBoundary.LOCK -> {
                        firstPlayArmed = true
                        lockPrePlayHand()
                    }
                    FirstPlayBoundary.NONE -> Unit
                }
            }
            if (firstPlayArmed) {
                captureRoiEvents(image)
            } else if (shouldProbeRanks(firstPlayArmed, stableHandSamples) &&
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
                            if (firstPlayArmed || revision != handRevision) return@post
                            if (rankRevision != revision) {
                                rankRevision = revision
                                lastRankCandidate = null
                                stableRankCandidates = 0
                            }
                            val positionCount = handCardPositionDetector.detect(luma)
                            val templateCards = handRankMatcher.detect(frame)
                            val yoloCards = handYoloFrame?.let(::detectYoloCards)
                            val cards = yoloCards?.cards?.takeIf { isValidBaseline(positionCount, it) } ?: templateCards
                            if (revision != handRevision) return@post
                            stableRankCandidates = stableRankCandidateCount(
                                lastRankCandidate,
                                stableRankCandidates,
                                cards
                            )
                            lastRankCandidate = cards.takeIf { it.total() > 0 }
                            if (cards.total() == 0) {
                                Log.i(TAG, "HAND_CANDIDATE_EMPTY positionCount=$positionCount")
                                return@post
                            }
                            val baselineValid = isValidBaseline(positionCount, cards)
                            if (stableRankCandidates >= 2 && baselineValid) {
                                lastStablePrePlayHand = cards
                                lastStablePositionCount = positionCount ?: 0
                            }
                            Log.i(
                                TAG,
                                "HAND_CANDIDATE stableRanks=$stableRankCandidates total=${cards.total()} " +
                                    "positionCount=$positionCount baselineValid=$baselineValid " +
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
        val left = image.width * 5 / 100
        val right = image.width * 95 / 100
        val top = image.height * 58 / 100
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

    private fun openingSignals(image: Image): OpeningSignals {
        val green = averageRgb(image, 510, 325, 600, 365)
        val controlsVisible = green[1] > 180 && green[1] > green[0] + 40 && green[1] > green[2] + 70
        var cardEdgeColumns = 0
        for (x in 480..800 step 4) {
            val belowWhite = (206..213).any { y -> isWhite(image, x, y) }
            val aboveWhite = (194..202).any { y -> isWhite(image, x, y) }
            if (belowWhite && !aboveWhite) cardEdgeColumns++
        }
        return OpeningSignals(controlsVisible, cardEdgeColumns >= 15)
    }

    private fun averageRgb(image: Image, left: Int, top: Int, right: Int, bottom: Int): IntArray {
        val result = IntArray(3)
        var samples = 0
        for (y in top until bottom step 8) for (x in left until right step 10) {
            val color = rgb(image, x, y)
            result.indices.forEach { result[it] += color[it] }
            samples++
        }
        result.indices.forEach { result[it] /= samples }
        return result
    }

    private fun isWhite(image: Image, canonicalX: Int, canonicalY: Int): Boolean =
        rgb(image, canonicalX, canonicalY).all { it >= 160 }

    private fun rgb(image: Image, canonicalX: Int, canonicalY: Int): IntArray {
        val plane = image.planes[0]
        val x = canonicalX * image.width / 1_280
        val y = canonicalY * image.height / 578
        val offset = y * plane.rowStride + x * plane.pixelStride
        return IntArray(3) { plane.buffer.get(offset + it).toInt() and 0xff }
    }

    /** 首手出牌前把最后稳定手牌快照锁定为 H0（张数与牌位检测一致，宽容 ±2）。 */
    private fun lockPrePlayHand() {
        val cards = lastStablePrePlayHand ?: return
        val repository = SessionRepository(this)
        val relock = repository.state.phase == SessionPhase.ACTIVE
        if (repository.state.phase != SessionPhase.PRE_PLAY && !relock) return
        repository.dispatch(
            if (relock) GameEvent.SelfHandRelocked(cards) else GameEvent.SelfHandLocked(cards)
        )
        OverlayService.refresh(this)
        val tag = if (relock) "SELF_HAND_RELOCKED" else "SELF_HAND_LOCKED"
        Log.i(TAG, "$tag total=${cards.total()} positionCount=$lastStablePositionCount cards=$cards")
    }

    /** 可用初版1：牌仍可见时连续两次相同候选即记账。 */
    private fun detectFormalPlays(
        ink: HandRankMatcher.InkFrame,
        visiblePlayZones: Map<PlayZone, Boolean>,
        yoloFrames: Map<PlayZone, Bitmap>,
        tableVisible: Boolean
    ) {
        if (!tableVisible) return
        val repository = SessionRepository(this)
        if (repository.state.phase != SessionPhase.ACTIVE) return
        val priming = primeSamplesRemaining > 0
        PlayZone.entries.forEach { zone ->
            val templateCards = handRankMatcher.detectPlay(ink, zone)
            val yoloCards = yoloFrames[zone]?.let { detectYoloCards(it, publicPlay = true) }
            val cards = yoloCards?.cards?.takeIf { it.total() > 0 } ?: templateCards
            val visible = isPlayVisible(visiblePlayZones[zone] == true)
            if (cards != lastPlayCandidates[zone]) {
                Log.i(
                    TAG,
                    "PLAY_CANDIDATE zone=$zone visible=$visible yolo=$yoloCards template=$templateCards cards=$cards"
                )
                lastPlayCandidates[zone] = cards
            }
            if (!visible) {
                stablePlayGate.update(zone, RankCounts.zero())
                unknownPlaySamples[zone] = 0
                return@forEach
            }
            if (priming) {
                stablePlayGate.prime(zone, cards)
                return@forEach
            }
            val unknownSamples = stableUnknownCount(unknownPlaySamples.getValue(zone), visible, cards)
            unknownPlaySamples[zone] = unknownSamples
            if (isStableUnknownPlay(unknownSamples)) {
                stablePlayGate.update(zone, RankCounts.zero())
                if (unknownSamples == UNKNOWN_SKIP_SAMPLES) {
                    Log.w(TAG, "PLAY_SKIPPED_UNKNOWN zone=$zone")
                }
                return@forEach
            }
            val play = stablePlayGate.update(zone, cards) ?: return@forEach
            val event = when (play.zone.source) {
                PlaySource.SELF -> GameEvent.SelfCardsPlayed(play.cards)
                PlaySource.OUTSIDE -> GameEvent.OutsideCardsPlayed(
                    play.cards,
                    seat = requireNotNull(play.zone.seat)
                )
            }
            runCatching { repository.dispatch(event) }
                .onSuccess {
                    OverlayService.refresh(this)
                    Log.i(TAG, "FORMAL_PLAY source=${play.zone.source} zone=${play.zone} cards=${play.cards}")
                }
                .onFailure {
                    repository.dispatch(GameEvent.SessionPaused(PauseReason.RANK_UNSTABLE))
                    OverlayService.refresh(this)
                    Log.w(TAG, "SESSION_PAUSED reason=RANK_UNSTABLE", it)
                }
        }
        if (priming) {
            primeSamplesRemaining -= 1
            if (primeSamplesRemaining == 0) Log.i(TAG, "PLAY_GATE_PRIMED_AFTER_RESTART")
        }
    }

    /** 截屏回调只缓存小 ROI；MNN 推理与记账均在清桌后进行。 */
    private fun captureRoiEvents(image: Image) {
        val tableVisible = gameTableVisible(image, SystemClock.elapsedRealtime())
        val visibleZones = if (tableVisible) playZoneVisibility(image) else emptyMap()
        val generation = captureGeneration
        val now = SystemClock.elapsedRealtime()
        val takeSample = now - lastRoiSampleAt >= ROI_SAMPLE_INTERVAL_MS
        var templateInk: BooleanArray? = null
        if (!tableVisible && restartPrimeDeadlineAt > 0L &&
            SystemClock.elapsedRealtime() >= restartPrimeDeadlineAt
        ) {
            roiEventCache.releasePrime(generation)
            restartPrimeDeadlineAt = 0L
            Log.i(TAG, "PLAY_GATE_PRIME_RELEASED_OUTSIDE_TABLE")
        }
        PlayZone.entries.forEach { zone ->
            val visible = tableVisible && isPlayVisible(visibleZones[zone] == true)
            if (takeSample && !visible) {
                rankHandler.post {
                    if (generation != captureGeneration) return@post
                    if (tableVisible) stablePlayGate.update(zone, RankCounts.zero())
                    if (zone.source == PlaySource.OUTSIDE) {
                        dispatchCountedPlays(
                            publicPlayCountAggregator.offer(
                                zone, generation, now, RankCounts.zero(), 0
                            )
                        )
                    }
                }
            }
            val frame = if (visible && takeSample && roiEventCache.needsFrame(zone, generation)) {
                val bitmap = publicPlayBitmap(image, zone)
                val ink = templateInk ?: handRankMatcher.snapshot(image).also { templateInk = it }
                RoiFrame(bitmap, roiQuality(bitmap), ink)
            } else {
                null
            }
            roiEventCache.observe(zone, tableVisible, visible, frame, generation)?.let { finalized ->
                Log.i(TAG, "ROI_FINALIZED zone=${finalized.zone} frames=${finalized.frames.size} early=${finalized.early}")
                rankHandler.post { finalizeRoiEvent(finalized) }
            }
        }
        if (takeSample) lastRoiSampleAt = now
    }

    private fun finalizeRoiEvent(finalized: RoiPlayEventCache.Finalized<RoiFrame>) {
        try {
            if (finalized.generation != captureGeneration || !firstPlayArmed) {
                if (finalized.generation != captureGeneration) {
                    Log.i(
                        TAG,
                        "FINALIZE_SKIP reason=GENERATION_MISMATCH zone=${finalized.zone} " +
                            "generation=${finalized.generation} current=$captureGeneration"
                    )
                } else {
                    Log.i(TAG, "FINALIZE_SKIP reason=NOT_ARMED zone=${finalized.zone}")
                }
                return
            }
            val templateCards = finalized.frames.maxByOrNull(RoiFrame::quality)?.let { frame ->
                handRankMatcher.detectPlay(handRankMatcher.prepare(frame.templateInk), finalized.zone)
            } ?: RankCounts.zero()
            val candidates = finalized.frames
                .sortedByDescending(RoiFrame::quality)
                .take(FINALIZE_FRAME_COUNT)
                .mapNotNull { frame ->
                    detectYoloCards(frame.bitmap, publicPlay = true)?.let {
                        RankCountsCandidate(
                            cards = fuseSinglePublicPlay(it.cards, templateCards),
                            minimumConfidence = it.minimumConfidence,
                            cardCount = it.cardCount
                        )
                    }
                }
            val winner = candidates
                .filter { it.cards.total() > 0 }
                .groupBy { it.cards }
                .maxWithOrNull(
                    compareBy<Map.Entry<RankCounts, List<RankCountsCandidate>>> { it.value.size }
                        .thenBy { entry -> entry.value.minOf { it.minimumConfidence } }
                )
            val cards = winner?.let { entry -> entry.key.takeIf {
                entry.value.map { it.cardCount }.distinct().size == 1 &&
                    (entry.value.size >= 2 || canFastConfirmPlay(it, entry.value.first().minimumConfidence))
            }
            }
            if (cards == null) {
                saveIssue(finalized, "NO_CONSENSUS", candidates)
                Log.w(TAG, "ROI_NO_CONSENSUS zone=${finalized.zone} candidates=$candidates")
                return
            }
            val repository = SessionRepository(this)
            if (repository.state.phase != SessionPhase.ACTIVE) {
                Log.i(
                    TAG,
                    "DISPATCH_SKIP reason=NOT_ACTIVE phase=${repository.state.phase} zone=${finalized.zone}"
                )
                return
            }
            val stable = stablePlayGate.acceptStable(
                finalized.zone,
                cards,
                winner!!.value.first().cardCount,
                SystemClock.elapsedRealtime()
            )
            if (stable == null) {
                Log.i(TAG, "FINALIZE_SKIP reason=DEDUP_HIT zone=${finalized.zone} cards=${cards}")
                return
            }
            val nowMs = SystemClock.elapsedRealtime()
            if (stable.zone.source == PlaySource.SELF) {
                val outsideZone = arbitrateSelfPlay(stable.cards, lastOutsideFinalized, nowMs)
                if (outsideZone != null) {
                    // 对家牌误入 SELF 区且外部区已入账：丢弃本次 SELF 幻影，避免重复扣外面。
                    Log.i(TAG, "ARBITRATED_DROP source=SELF->$outsideZone cards=${stable.cards}")
                    return
                }
                dispatchGameEvent(GameEvent.SelfCardsPlayed(stable.cards), stable.zone, stable.cardCount)
                return
            }
            // 留存实际入账的公开牌 ROI；仅供下一局逐张核验，不影响计数。
            lastOutsideFinalized = lastOutsideFinalized + (finalized.zone to (stable.cards to nowMs))
            saveIssue(finalized, "ACCEPTED", candidates)
            val offeredAt = SystemClock.elapsedRealtime()
            val offered = publicPlayCountAggregator.offer(
                finalized.zone,
                finalized.generation,
                offeredAt,
                stable.cards,
                stable.cardCount
            )
            rankHandler.postDelayed({
                if (finalized.generation == captureGeneration) {
                    dispatchCountedPlays(publicPlayCountAggregator.flushExpired(finalized.zone, finalized.generation, SystemClock.elapsedRealtime()))
                }
            }, 301L)
            if (offered.isNotEmpty()) dispatchCountedPlays(offered)
        } finally {
            if (finalized.early) roiEventCache.completeEarly(finalized.zone, finalized.generation)
            finalized.frames.forEach { it.bitmap.recycle() }
        }
    }

    private fun dispatchCountedPlays(plays: List<CountedPlay>) {
        if (captureGeneration <= 0L) return
        val repository = SessionRepository(this)
        if (repository.state.phase != SessionPhase.ACTIVE) {
            Log.i(TAG, "DISPATCH_SKIP reason=NOT_ACTIVE phase=${repository.state.phase}")
            return
        }
        plays.forEach { play ->
            if (play.generation != captureGeneration) return@forEach
            if (play.zone.source != PlaySource.OUTSIDE) return@forEach
            runCatching {
                repository.dispatch(
                    GameEvent.OutsideCardsPlayed(
                        play.cards,
                        seat = requireNotNull(play.zone.seat),
                        cardCount = play.cardCount
                    )
                )
            }.onSuccess {
                lastPlayRecordedAt = SystemClock.elapsedRealtime()
                OverlayService.refresh(this)
                Log.i(
                    TAG,
                    "FORMAL_PLAY source=${play.zone.source} zone=${play.zone} seat=${play.zone.seat} " +
                        "cardCount=${play.cardCount} cardsTotal=${play.cards.total()} generation=${play.generation}"
                )
            }
                .onFailure {
                    Log.w(
                        TAG,
                        "PLAY_REJECTED reason=OUT_OF_STOCK zone=${play.zone} seat=${play.zone.seat} " +
                            "cards=${play.cards} cardCount=${play.cardCount}",
                        it
                    )
                }
        }
    }

    private fun dispatchGameEvent(event: GameEvent, zone: PlayZone, cardCount: Int) {
        val repository = SessionRepository(this)
        if (repository.state.phase != SessionPhase.ACTIVE) {
            Log.i(TAG, "DISPATCH_SKIP reason=NOT_ACTIVE phase=${repository.state.phase} zone=$zone")
            return
        }
        runCatching { repository.dispatch(event) }
            .onSuccess {
                lastPlayRecordedAt = SystemClock.elapsedRealtime()
                OverlayService.refresh(this)
                val cardsTotal = when (event) {
                    is GameEvent.SelfCardsPlayed -> event.cards.total()
                    is GameEvent.OutsideCardsPlayed -> event.cards.total()
                    else -> 0
                }
                Log.i(
                    TAG,
                    "FORMAL_PLAY source=${zone.source} zone=$zone seat=${zone.seat} " +
                        "cardCount=$cardCount cardsTotal=$cardsTotal generation=$captureGeneration"
                )
            }
            .onFailure {
                val rejectedCards = when (event) {
                    is GameEvent.SelfCardsPlayed -> event.cards
                    is GameEvent.OutsideCardsPlayed -> event.cards
                    else -> RankCounts.zero()
                }
                Log.w(
                    TAG,
                    "PLAY_REJECTED reason=OUT_OF_STOCK zone=$zone seat=${zone.seat} " +
                        "cards=$rejectedCards cardCount=$cardCount",
                    it
                )
            }
    }

    private fun saveIssue(
        finalized: RoiPlayEventCache.Finalized<RoiFrame>,
        reason: String,
        candidates: List<RankCountsCandidate>
    ) {
        finalized.frames.maxByOrNull(RoiFrame::quality)?.let { frame ->
            issueSampleStore.save(frame.bitmap, finalized.zone, reason, candidates)
        }
    }

    private fun roiQuality(bitmap: Bitmap): Int {
        var quality = 0
        for (y in 1 until bitmap.height step 3) for (x in 1 until bitmap.width step 3) {
            val color = bitmap.getPixel(x, y)
            val left = bitmap.getPixel(x - 1, y)
            quality += kotlin.math.abs(android.graphics.Color.red(color) - android.graphics.Color.red(left))
        }
        return quality
    }

    private data class RoiFrame(
        val bitmap: Bitmap,
        val quality: Int,
        val templateInk: BooleanArray
    )

    private fun detectYoloCards(bitmap: Bitmap, publicPlay: Boolean = false): YoloCards? = runCatching {
        val counts = Rank.entries.associateWith { 0 }.toMutableMap()
        val detections = requireNotNull(publicPlayYolo).detect(bitmap)
            .filter { !publicPlay || isPublicRankBand(it.centerY) }
        detections.forEach { detection ->
            counts[detection.rank] = counts.getValue(detection.rank) + 1
        }
        YoloCards(RankCounts.of(counts), detections.minOfOrNull { it.confidence } ?: 0f, detections.size)
    }.onFailure { Log.w(TAG, "YOLO_PLAY_FAILED", it) }.getOrNull()

    private data class YoloCards(val cards: RankCounts, val minimumConfidence: Float, val cardCount: Int)

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

    /** 桌面判定：多点采样中央桌面区域，绿点占比 >=60%，或最近 5 秒内有出牌活动即视为可见。 */
    private fun gameTableVisible(image: Image, now: Long): Boolean {
        val samplePoints = listOf(
            480 to 170, 640 to 170, 800 to 170,
            420 to 240, 640 to 240, 860 to 240,
            520 to 310, 760 to 310
        )
        val greenCount = samplePoints.count { (x, y) ->
            val color = rgb(image, x, y)
            isTableGreen(color[0], color[1], color[2])
        }
        return isTableVisibleBySamples(
            greenCount,
            samplePoints.size,
            now - lastPlayRecordedAt <= TABLE_ACTIVITY_GRACE_MS
        )
    }

    private fun resetSessionTracking(firstPlayIsArmed: Boolean) {
        if (!firstPlayIsArmed) primeSamplesRemaining = 0
        val generation = ++captureGeneration
        roiEventCache.reset(generation, primeExistingTable = firstPlayIsArmed)
        restartPrimeDeadlineAt = if (firstPlayIsArmed) {
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
        firstPlayArmed = firstPlayIsArmed
        lastStablePrePlayHand = null
        lastStablePositionCount = 0
        lastPlayRecordedAt = 0L
        lastOutsideFinalized = emptyMap()
        firstPlayGate.reset()
        rankHandler.post {
            lastRankCandidate = null
            stableRankCandidates = 0
            rankRevision = -1L
            stablePlayGate.reset()
            publicPlayCountAggregator.reset()
            PlayZone.entries.forEach {
                unknownPlaySamples[it] = 0
                lastPlayCandidates[it] = null
            }
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
        private const val ACTION_RESET_SESSION = "com.example.cardcounter.RESET_CAPTURE_SESSION"
        private const val ACTION_RELOCK_HAND = "com.example.cardcounter.RELOCK_HAND"
        private const val REPORT_INTERVAL_MS = 1_000L
        private const val HAND_SAMPLE_INTERVAL_MS = 250L
        private const val STABLE_CHANGE_PERCENT = 1.5
        private const val HAND_SIGNATURE_COLUMNS = 12
        private const val HAND_SIGNATURE_ROWS = 8
        private const val RANK_PROBE_INTERVAL_MS = 250L
        private const val PLAY_VISIBILITY_STEP = 8
        private const val PLAY_WHITE_LIMIT = 190
        private const val UNKNOWN_SKIP_SAMPLES = 3
        private const val RESTART_PRIME_SAMPLES = 3
        private const val ROI_SAMPLE_INTERVAL_MS = 50L
        private const val FINALIZE_FRAME_COUNT = 3
        private const val RESTART_TABLE_GRACE_MS = 2_000L
        private const val TABLE_ACTIVITY_GRACE_MS = 5_000L
        private const val HAND_YOLO_LEFT = 180
        private const val HAND_YOLO_RIGHT = 1_050
        private const val HAND_YOLO_TOP = 350
        private const val HAND_YOLO_BOTTOM = 490
        private const val HAND_YOLO_PAD_TOP = 540
        private const val TAG = "CaptureProbe"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureProbeService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun resetSession(context: Context) {
            if (!CaptureProbeStatus.snapshot().running) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, CaptureProbeService::class.java).setAction(ACTION_RESET_SESSION)
            )
        }

        /** 触发重新识别手牌：只重启用探测并重锁 H0，不 newGame、不清账。 */
        fun relockHand(context: Context) {
            if (!CaptureProbeStatus.snapshot().running) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, CaptureProbeService::class.java).setAction(ACTION_RELOCK_HAND)
            )
        }
    }
}
