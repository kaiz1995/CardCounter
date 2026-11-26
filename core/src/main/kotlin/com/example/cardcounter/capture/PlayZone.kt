package com.example.cardcounter.capture

import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.domain.OutsideSeat
import com.example.cardcounter.recognition.GoujiPlayRules
import com.example.cardcounter.vision.Raster
import com.example.cardcounter.vision.ScreenScale
import kotlin.math.roundToInt

/** 固定皮肤的六个公开出牌区；中央仅代表自己，其余五区代表外面。 */
enum class PlayZone(
    val source: PlaySource,
    val seat: OutsideSeat?,
    private val leftRatio: Double,
    private val rightRatio: Double,
    private val rankYRatio: Double,
    /** “出牌”倒计时圆标的中心（1280x578 画布坐标）；挂王时牌还摆着，圆标会往上偏，见 [TurnBadge]。 */
    val badgeX: Int,
    val badgeY: Int,
    val visibilityHeight: Int = 70
) {
    TOP(PlaySource.OUTSIDE, OutsideSeat.TOP, 0.539, 0.602, 0.022, 780, 62),
    LEFT_TOP(PlaySource.OUTSIDE, OutsideSeat.LEFT_TOP, 0.148, 0.266, 0.156, 298, 130, 56),
    LEFT_BOTTOM(PlaySource.OUTSIDE, OutsideSeat.LEFT_BOTTOM, 0.141, 0.281, 0.429, 298, 290),
    RIGHT_TOP(PlaySource.OUTSIDE, OutsideSeat.RIGHT_TOP, 0.750, 0.859, 0.156, 978, 130, 56),
    RIGHT_BOTTOM(PlaySource.OUTSIDE, OutsideSeat.RIGHT_BOTTOM, 0.750, 0.859, 0.429, 978, 290),
    SELF(PlaySource.SELF, null, 0.438, 0.563, 0.358, 638, 264, 28);

    fun left(width: Int): Int = (width * leftRatio).roundToInt()

    fun right(width: Int): Int = (width * rightRatio).roundToInt()

    fun rankY(height: Int): Int = (height * rankYRatio).roundToInt()

    /** Public-play crop keeps aspect ratio; caller letterboxes into the 320 model input. */
    fun yoloCrop(width: Int, height: Int): PlayCrop {
        val left = left(width)
        val cropWidth = right(width) - left + 1
        val cropHeight = if (source == PlaySource.SELF) cropWidth else visibilityHeight
        val top = if (source == PlaySource.SELF) {
            (rankY(height) - cropHeight * 43 / 100).coerceIn(0, height - cropHeight)
        } else {
            (rankY(height) - cropHeight / 2).coerceIn(0, height - cropHeight)
        }
        return PlayCrop(left, top, cropWidth, cropHeight)
    }
}

data class PlayCrop(val left: Int, val top: Int, val width: Int, val height: Int)

enum class PlaySource { SELF, OUTSIDE }

data class StablePlay(val zone: PlayZone, val cards: RankCounts)

/** 只有外面五家的出牌进账；自己那一区的出牌完全不管，返回 null。 */
fun StablePlay.toGameEvent(): GameEvent? = when (zone.source) {
    PlaySource.SELF -> null
    PlaySource.OUTSIDE -> GameEvent.OutsideCardsPlayed(cards, seat = requireNotNull(zone.seat))
}

/** 重复/冲突拦截原因。 */
enum class PlaySuppressionReason {
    EMPTY,
    EXACT_DUPLICATE,
    SUBSET_OR_SUPERSET
}

/** 去重门只读检查结果（不修改门控状态）。 */
sealed interface PlayGateDecision {
    data class Accept(val play: StablePlay) : PlayGateDecision
    data class Suppressed(val reason: PlaySuppressionReason) : PlayGateDecision
}

/**
 * 去重门：每区记“上一次入账的牌”“入账时间”和“入账后是否已解锁下一手”。
 * 1. 先调用 [check] 判断是否放行（不修改状态）；只有账本真正 Accepted 后才调用 [commitAccepted] 更新状态，
 *    避免账本拒绝（Rejected/Paused）后门控状态失真吞掉后续合法出牌。check / commitAccepted 都带时间戳（毫秒，同一时钟）。
 * 2. 未解锁（没轮到过这一家、没真正长清桌、也没经清桌+他座出牌）时：
 *    - 与上一手完全相同：判 [PlaySuppressionReason.EXACT_DUPLICATE]，不限时间；
 *    - 与上一手主点数相同且互为子集或超集（例如漏掉边缘牌重认补全）：只在上次入账后 [SUBSET_WINDOW_MS] 内
 *      判 [PlaySuppressionReason.SUBSET_OR_SUPERSET]。超出时间窗就不再是“同一手补全重认”，
 *      而是挂王没人要、旧牌没收走就盖牌续出同主点数的新一手，必须放行；
 *    - 只有主点数不同的全新牌（例如挂王后直接盖牌）才放行。
 * 3. 解锁条件（共用同一解锁状态）：
 *    - 本区“出牌”圆标确认轮到这一家（[noteTurn]）；
 *    - 本区经历过一次真实的长清桌（连续空桌 >= 1.5s，[noteLongClear]）；
 *    - 本区已经清桌（[noteZoneCleared]）之后，别的座位有新的出牌入账（[commitAccepted]）。
 *      顺序不能反：本区还没清桌时别的座位入账不算，否则旧牌被特效遮挡闪空一下就会解锁，旧牌重现被重扣。
 * 清桌不清“上一次入账的牌”；只有 [reset]（新局）全清，[noteReverted] 仅清理被撤销座位的记录。
 */
class StablePlayGate {
    private class ZoneState {
        var accepted: RankCounts? = null
        var acceptedAtMs = 0L
        var unlockedSinceAccept = false
        var clearedSinceAccept = false
    }

    private val states = PlayZone.entries.associateWith { ZoneState() }

    /** 只判断不修改状态；供提交账本前使用。 */
    fun check(zone: PlayZone, cards: RankCounts, timestampMs: Long): PlayGateDecision {
        if (cards.total() == 0) return PlayGateDecision.Suppressed(PlaySuppressionReason.EMPTY)
        val state = states.getValue(zone)
        val previous = state.accepted
        if (previous != null && !state.unlockedSinceAccept) {
            if (previous == cards) {
                return PlayGateDecision.Suppressed(PlaySuppressionReason.EXACT_DUPLICATE)
            }
            if (timestampMs - state.acceptedAtMs <= SUBSET_WINDOW_MS &&
                isSameMainRankSubsetOrSuperset(previous, cards)
            ) {
                return PlayGateDecision.Suppressed(PlaySuppressionReason.SUBSET_OR_SUPERSET)
            }
        }
        return PlayGateDecision.Accept(StablePlay(zone, cards))
    }

    /** 账本真正接受（Accepted）之后再更新门控状态，同时给已清桌的其他座位解锁。 */
    fun commitAccepted(zone: PlayZone, cards: RankCounts, timestampMs: Long) {
        if (cards.total() == 0) return
        val current = states.getValue(zone)
        current.accepted = cards
        current.acceptedAtMs = timestampMs
        current.unlockedSinceAccept = false
        current.clearedSinceAccept = false
        if (zone.source == PlaySource.OUTSIDE) {
            states.forEach { (otherZone, otherState) ->
                if (otherZone != zone && otherZone.source == PlaySource.OUTSIDE &&
                    otherState.accepted != null && otherState.clearedSinceAccept
                ) {
                    otherState.unlockedSinceAccept = true
                }
            }
        }
    }

    /** 便捷方法：检查并立即提交（单测及不区分 Rejected 的简单调用）。 */
    fun accept(zone: PlayZone, cards: RankCounts, timestampMs: Long = 0L): StablePlay? =
        when (val decision = check(zone, cards, timestampMs)) {
            is PlayGateDecision.Accept -> {
                commitAccepted(zone, cards, timestampMs)
                decision.play
            }
            is PlayGateDecision.Suppressed -> null
        }

    /** 这一区的玩家轮到过了（见 [TurnTracker]）：之后再出一样的牌也算新的一手。 */
    fun noteTurn(zone: PlayZone) {
        states.getValue(zone).unlockedSinceAccept = true
    }

    /** 本区经历过一次连续空桌 >= 1.5s 的真实长清桌，解锁本区。 */
    fun noteLongClear(zone: PlayZone) {
        val state = states.getValue(zone)
        state.clearedSinceAccept = true
        state.unlockedSinceAccept = true
    }

    /**
     * 本区经历过常规清桌（牌已从桌面收走）：只记下已清桌，不解锁。
     * 之后别的座位入账（[commitAccepted]）才会解锁本区；清桌之前的他座入账不算。
     */
    fun noteZoneCleared(zone: PlayZone) {
        val state = states.getValue(zone)
        if (state.accepted == null) return
        state.clearedSinceAccept = true
    }

    /** 人工撤销某座位的一手出牌后，清空该区上次入账记忆，避免错误记忆继续拦截后续出牌。 */
    fun noteReverted(seat: OutsideSeat) {
        PlayZone.entries.filter { it.seat == seat }.forEach { zone ->
            states.getValue(zone).apply {
                accepted = null
                acceptedAtMs = 0L
                unlockedSinceAccept = false
                clearedSinceAccept = false
            }
        }
    }

    fun reset() {
        states.values.forEach {
            it.accepted = null
            it.acceptedAtMs = 0L
            it.unlockedSinceAccept = false
            it.clearedSinceAccept = false
        }
    }

    companion object {
        /**
         * 子集/超集拦截只在上次入账后这么久内生效：用来拦同一手牌画面闪一下后补全或漏牌的重认
         * （重新停稳要 15 次采样约 0.75 秒，加识别耗时，1.5 秒内）。超出就放行：旧牌没收走、
         * 挂王没人要时直接盖上同主点数（或纯配牌）的新一手，不能被永久当成重认拦掉。
         */
        const val SUBSET_WINDOW_MS = 1_500L
    }
}

/**
 * 清桌上升沿：[RoiPlayEventCache.isCleared] 在整段空桌期间一直是 true，只在由 false 变 true 的那一次告诉去重门，
 * 空桌期间不要每个采样都投递（旧的投递会排在新一手入账之后，把新一手的清桌标志误置）。手机和电脑评测共用。
 */
class ZoneClearEdge {
    private val last = BooleanArray(PlayZone.entries.size)

    /** [cleared] 是这一区这一次采样的清桌状态；返回 true 表示刚刚从没清桌变成清桌。 */
    @Synchronized
    fun rose(zone: PlayZone, cleared: Boolean): Boolean {
        val before = last[zone.ordinal]
        last[zone.ordinal] = cleared
        return cleared && !before
    }

    @Synchronized
    fun reset() {
        last.fill(false)
    }
}

/** 两手牌主点数相同（均为同一普通牌，或均无普通牌只有配牌），且互为子集或超集。 */
fun isSameMainRankSubsetOrSuperset(previous: RankCounts, current: RankCounts): Boolean {
    val prevMain = Rank.entries.filterTo(HashSet()) { it !in GoujiPlayRules.WILD_RANKS && previous[it] > 0 }
    val currMain = Rank.entries.filterTo(HashSet()) { it !in GoujiPlayRules.WILD_RANKS && current[it] > 0 }
    if (prevMain != currMain) return false
    return previous.fitsWithin(current) || current.fitsWithin(previous)
}

/** 牌面低饱和亮块阈值（max - min <= 45 且 max >= 115），兼容烧牌/开点压暗，排除绿桌面与暗背景。 */
const val CARD_SURFACE_MIN_BRIGHTNESS = 115
const val CARD_SURFACE_MAX_SATURATION = 45
const val PLAY_VISIBILITY_STEP = 4

fun isCardSurfaceRgb(rgb: Int): Boolean {
    val red = (rgb shr 16) and 0xff
    val green = (rgb shr 8) and 0xff
    val blue = rgb and 0xff
    return isCardSurfaceColor(red, green, blue)
}

fun isCardSurfaceColor(red: Int, green: Int, blue: Int): Boolean {
    val high = maxOf(red, green, blue)
    val low = minOf(red, green, blue)
    return high >= CARD_SURFACE_MIN_BRIGHTNESS && high - low <= CARD_SURFACE_MAX_SATURATION
}

/** 在 1280×578 画布坐标下采样判断单个出牌区是否存在可见牌面（低饱和亮块占比 >= 10%）。 */
fun isZoneCardVisible(
    zone: PlayZone,
    step: Int = PLAY_VISIBILITY_STEP,
    pixel: (canonicalX: Int, canonicalY: Int) -> Int
): Boolean {
    val left = zone.left(ScreenScale.CANVAS_WIDTH)
    val right = zone.right(ScreenScale.CANVAS_WIDTH)
    val top = zone.rankY(ScreenScale.CANVAS_HEIGHT)
    val bottom = top + zone.visibilityHeight
    var matched = 0
    var samples = 0
    var y = top
    while (y <= bottom) {
        var x = left
        while (x <= right) {
            if (isCardSurfaceRgb(pixel(x, y))) matched++
            samples++
            x += step
        }
        y += step
    }
    return hasVisibleCardArea(matched, samples)
}

/** 直接从 [Raster] 采样判断六个出牌区的可见性（无数组分配）。 */
fun playZoneVisibility(frame: Raster, step: Int = PLAY_VISIBILITY_STEP): Map<PlayZone, Boolean> =
    PlayZone.entries.associateWith { zone ->
        val left = zone.left(ScreenScale.CANVAS_WIDTH)
        val right = zone.right(ScreenScale.CANVAS_WIDTH)
        val top = zone.rankY(ScreenScale.CANVAS_HEIGHT)
        val bottom = top + zone.visibilityHeight
        var matched = 0
        var samples = 0
        var y = top
        while (y <= bottom) {
            val sy = (y * frame.height / ScreenScale.CANVAS_HEIGHT).coerceIn(0, frame.height - 1)
            var x = left
            while (x <= right) {
                val sx = (x * frame.width / ScreenScale.CANVAS_WIDTH).coerceIn(0, frame.width - 1)
                if (isCardSurfaceRgb(frame.rgb(sx, sy))) matched++
                samples++
                x += step
            }
            y += step
        }
        hasVisibleCardArea(matched, samples)
    }

/** 牌桌绿点判定（1280×578 画布坐标 (500, 200) 和 (800, 200)）。 */
fun isTableVisible(frame: Raster): Boolean =
    isTablePointGreen(frame, 500, 200) && isTablePointGreen(frame, 800, 200)

private fun isTablePointGreen(frame: Raster, canonicalX: Int, canonicalY: Int): Boolean {
    val sx = (canonicalX * frame.width / ScreenScale.CANVAS_WIDTH).coerceIn(0, frame.width - 1)
    val sy = (canonicalY * frame.height / ScreenScale.CANVAS_HEIGHT).coerceIn(0, frame.height - 1)
    val rgb = frame.rgb(sx, sy)
    return isTableGreen((rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff)
}

/** YOLO会把“过牌”等文字误识别成牌，必须同时存在白色牌面。 */
fun isPlayVisible(whiteAreaVisible: Boolean): Boolean = whiteAreaVisible

/** 单张牌在最宽的侧边区域约占14.5%，阈值留到10%避免漏掉深色花纹。 */
fun hasVisibleCardArea(whiteSamples: Int, totalSamples: Int): Boolean =
    totalSamples > 0 && whiteSamples * 100 >= totalSamples * 10

fun isTableGreen(red: Int, green: Int, blue: Int): Boolean =
    green >= 70 && green - red >= 30 && green - blue >= 10

/** 只用于旧模型回放（eval 的 LegacyPipeline）；新模型的框只框点数，手机端不再按纵坐标过滤。 */
fun isPublicRankBand(centerY: Float): Boolean = centerY < 0.85f

/** 手牌识别在后台一直跑，只在手牌画面停稳后探测；手牌一旦确认，本局不再识别。 */
fun shouldProbeHand(handConfirmed: Boolean, stableHandSamples: Int): Boolean =
    !handConfirmed && stableHandSamples >= 3

enum class MidGameCheckResult { PENDING, CLEAR, SKIP }

/**
 * 中途打开判定：只统计 OUTSIDE 区（交贡/宣点/买牌弹窗常占 SELF 中央区，不算），
 * 牌桌可见后连续 [requiredConsecutive] 次采样都看到 OUTSIDE 牌面才判中途打开；
 * 只要看到过一次 OUTSIDE 全空桌（或已确认手牌/已记过外面牌），立即结束检查，正常记牌。
 */
class MidGameOpenDetector(private val requiredConsecutive: Int = DEFAULT_CONSECUTIVE) {
    private var pending = false
    private var consecutiveOutsideVisible = 0

    fun reset(checkMidOpen: Boolean) {
        pending = checkMidOpen
        consecutiveOutsideVisible = 0
    }

    fun observe(
        handConfirmed: Boolean,
        outsidePlayedCount: Int,
        tableVisible: Boolean,
        visibleZones: Map<PlayZone, Boolean>
    ): MidGameCheckResult {
        if (!pending) return MidGameCheckResult.CLEAR
        if (handConfirmed || outsidePlayedCount > 0) {
            pending = false
            consecutiveOutsideVisible = 0
            return MidGameCheckResult.CLEAR
        }
        if (!tableVisible) {
            consecutiveOutsideVisible = 0
            return MidGameCheckResult.PENDING
        }
        val outsideVisible = PlayZone.entries.any {
            it.source == PlaySource.OUTSIDE && visibleZones[it] == true
        }
        if (!outsideVisible) {
            pending = false
            consecutiveOutsideVisible = 0
            return MidGameCheckResult.CLEAR
        }
        consecutiveOutsideVisible++
        if (consecutiveOutsideVisible >= requiredConsecutive) {
            pending = false
            return MidGameCheckResult.SKIP
        }
        return MidGameCheckResult.PENDING
    }

    companion object {
        const val DEFAULT_CONSECUTIVE = 5
    }
}

/** 兼容单帧判断辅助：只看 OUTSIDE 区数量。 */
fun shouldSkipOpenedGame(handConfirmed: Boolean, outsidePlayedCount: Int, visibleOutsideZones: Int): Boolean =
    !handConfirmed && outsidePlayedCount == 0 && visibleOutsideZones > 0

/**
 * 开局换牌期（发牌、交贡、还贡、买三买四、宣点）门控：
 * 直到（1）手牌已确认，或（2）任意 OUTSIDE 区的“出牌”圆标被 [TurnTracker] 连续帧确认轮到他（[onOutsideTurn]），
 * 才认为已进入正式出牌阶段。不能只看单帧圆标颜色：换牌期特效闪一帧红黄像素就会永久放行；
 * 也不采信 SELF 圆标，因为宣点阶段中央红底“宣”按钮位于 SELF 圆标位会误触发。
 *
 * 首出的牌如果在门控打开前被丢掉（[notePrePlaySkipped]，常见于首出圆标漏判、下家圆标先亮），
 * 门控由圆标打开时会把仍摆在桌上、门控打开前 [RETRY_WINDOW_MS] 内交出的那几手交还给调用方补记，
 * 补记仍走去重门和规则校验。因确认手牌打开门控时不补记：确认手牌常发生在换牌期，桌上可能是交贡展示牌。
 * 本区清桌（[noteZoneCleared]）后，这一区被丢的牌不再补记。
 */
class OpeningPlayGate {
    /** 门控打开前被丢掉的一手：出牌区、点数和丢掉的时间（毫秒，与调用方其余时间同一时钟）。 */
    data class SkippedPlay(val zone: PlayZone, val cards: RankCounts, val atMs: Long)

    private var enteredPlayPhase = false
    private val skipped = HashMap<PlayZone, SkippedPlay>()

    /** [alreadyInPlay]：接着上一局记，本局已经在出牌阶段。新局重置为 false，换牌期重新拦。 */
    @Synchronized
    fun reset(alreadyInPlay: Boolean) {
        enteredPlayPhase = alreadyInPlay
        skipped.clear()
    }

    /**
     * 某个 OUTSIDE 区的“出牌”圆标被连续帧确认（[TurnUpdate.turned]）。
     * 这一次刚好把门控打开时，返回要补记的那几手（按丢掉的先后）；其余情况返回空列表。
     * [isOnTable]：这一区牌现在还摆在桌上。
     */
    @Synchronized
    fun onOutsideTurn(turned: Boolean, nowMs: Long, isOnTable: (PlayZone) -> Boolean): List<SkippedPlay> {
        if (!turned || enteredPlayPhase) return emptyList()
        enteredPlayPhase = true
        val retries = skipped.values
            .filter { nowMs - it.atMs <= RETRY_WINDOW_MS && isOnTable(it.zone) }
            .sortedBy { it.atMs }
        skipped.clear()
        return retries
    }

    @Synchronized
    fun isReady(handConfirmed: Boolean): Boolean {
        if (handConfirmed && !enteredPlayPhase) {
            enteredPlayPhase = true
            // 因确认手牌打开：换牌期桌上可能是交贡展示牌，丢掉的不补记。
            skipped.clear()
        }
        return enteredPlayPhase
    }

    /** 门控还没打开时丢掉了这一区的一手；同一区只记最近一手。 */
    @Synchronized
    fun notePrePlaySkipped(zone: PlayZone, cards: RankCounts, atMs: Long) {
        if (enteredPlayPhase) return
        skipped[zone] = SkippedPlay(zone, cards, atMs)
    }

    /** 这一区清桌了：牌已经收走，丢掉的那手不再补记。 */
    @Synchronized
    fun noteZoneCleared(zone: PlayZone) {
        skipped.remove(zone)
    }

    companion object {
        /** 补记只认门控打开前这么久内交出的手。 */
        const val RETRY_WINDOW_MS = 8_000L
    }
}

/** 多张且每张都很确定时不等待下一帧，避免开点动画清桌前丢失整手。 */
fun canFastConfirmPlay(cards: RankCounts, minimumConfidence: Float): Boolean =
    cards.total() >= 3 && minimumConfidence >= 0.60f
