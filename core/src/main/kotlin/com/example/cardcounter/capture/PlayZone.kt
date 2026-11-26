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
    val visibilityHeight: Int = 70,
    private val expandDirection: ExpandDirection = ExpandDirection.NONE,
    private val expandLimitRatio: Double = 0.0
) {
    TOP(PlaySource.OUTSIDE, OutsideSeat.TOP, 0.539, 0.602, 0.022, 780, 62, expandDirection = ExpandDirection.RIGHT, expandLimitRatio = 1_040.0 / 1_280.0),
    LEFT_TOP(PlaySource.OUTSIDE, OutsideSeat.LEFT_TOP, 0.148, 0.266, 0.156, 298, 130, 56, ExpandDirection.RIGHT, 435.0 / 1_280.0),
    LEFT_BOTTOM(PlaySource.OUTSIDE, OutsideSeat.LEFT_BOTTOM, 0.141, 0.281, 0.429, 298, 290, expandDirection = ExpandDirection.RIGHT, expandLimitRatio = 560.0 / 1_280.0),
    RIGHT_TOP(PlaySource.OUTSIDE, OutsideSeat.RIGHT_TOP, 0.750, 0.859, 0.156, 978, 130, 56, ExpandDirection.LEFT, 795.0 / 1_280.0),
    RIGHT_BOTTOM(PlaySource.OUTSIDE, OutsideSeat.RIGHT_BOTTOM, 0.750, 0.859, 0.429, 978, 290, expandDirection = ExpandDirection.LEFT, expandLimitRatio = 720.0 / 1_280.0),
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

    /** 长串大牌（7张及以上）会超出固定裁剪框，沿出牌延伸方向逐列检测牌面白色边界并扩展，不超过安全边界。 */
    fun expandedYoloCrop(
        width: Int,
        height: Int,
        isCardWhiteColumn: (x: Int, top: Int, bottom: Int) -> Boolean
    ): PlayCrop {
        val base = yoloCrop(width, height)
        if (source == PlaySource.SELF) return base
        val top = base.top
        val bottom = base.top + base.height
        return when (expandDirection) {
            ExpandDirection.NONE -> base
            ExpandDirection.LEFT -> expandLeft(base, width, top, bottom, isCardWhiteColumn)
            ExpandDirection.RIGHT -> expandRight(base, width, top, bottom, isCardWhiteColumn)
        }
    }

    private fun expandLeft(
        base: PlayCrop,
        width: Int,
        top: Int,
        bottom: Int,
        isCardWhiteColumn: (x: Int, top: Int, bottom: Int) -> Boolean
    ): PlayCrop {
        val edgeEnd = minOf(base.left + base.width, base.left + EDGE_CHECK_PX)
        val touchesEdge = (base.left until edgeEnd).any { x -> isCardWhiteColumn(x, top, bottom) }
        if (!touchesEdge) return base
        val limit = (width * expandLimitRatio).roundToInt().coerceAtLeast(0)
        var lastWhite = base.left
        var gap = 0
        for (x in (base.left - 1) downTo limit) {
            if (isCardWhiteColumn(x, top, bottom)) {
                lastWhite = x
                gap = 0
            } else {
                gap++
                if (gap >= GAP_STOP_PX) break
            }
        }
        if (lastWhite >= base.left) return base
        val newLeft = maxOf(limit, lastWhite - EXPAND_MARGIN_PX)
        return PlayCrop(newLeft, base.top, base.left + base.width - newLeft, base.height)
    }

    private fun expandRight(
        base: PlayCrop,
        width: Int,
        top: Int,
        bottom: Int,
        isCardWhiteColumn: (x: Int, top: Int, bottom: Int) -> Boolean
    ): PlayCrop {
        val baseRight = base.left + base.width
        val edgeStart = maxOf(base.left, baseRight - EDGE_CHECK_PX)
        val touchesEdge = (edgeStart until baseRight).any { x -> isCardWhiteColumn(x, top, bottom) }
        if (!touchesEdge) return base
        val limit = (width * expandLimitRatio).roundToInt().coerceAtMost(width)
        var lastWhite = baseRight - 1
        var gap = 0
        for (x in baseRight until limit) {
            if (isCardWhiteColumn(x, top, bottom)) {
                lastWhite = x
                gap = 0
            } else {
                gap++
                if (gap >= GAP_STOP_PX) break
            }
        }
        if (lastWhite < baseRight) return base
        val newRight = minOf(limit, lastWhite + 1 + EXPAND_MARGIN_PX)
        return PlayCrop(base.left, base.top, newRight - base.left, base.height)
    }

    private companion object {
        const val EDGE_CHECK_PX = 12
        const val GAP_STOP_PX = 12
        const val EXPAND_MARGIN_PX = 10
    }
}

private enum class ExpandDirection { NONE, LEFT, RIGHT }

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
 *    - 本区已经清桌（[noteZoneCleared]）之后，别的座位有新的出牌入账（[commitAccepted]），且同时满足：
 *      a) 入账那一刻本区仍是清桌状态（[commitAccepted] 的 isStillCleared 现场回查），清桌后旧牌又回来的不算；
 *      b) 本区最近一次清桌上升沿距这次入账已满 [OTHER_SEAT_UNLOCK_MIN_CLEAR_MS]，瞬时遮挡闪空的不算。
 *      顺序不能反：本区还没清桌时别的座位入账不算，否则旧牌被特效遮挡闪空一下就会解锁，旧牌重现被重扣。
 *      清桌标记本身是粘性的（旧牌回来也不会自动清掉），所以上面 a)、b) 两道现场校验缺一不可。
 * 清桌不清“上一次入账的牌”；只有 [reset]（新局）全清，[noteReverted] 仅清理被撤销座位的记录。
 */
class StablePlayGate {
    private class ZoneState {
        var accepted: RankCounts? = null
        var acceptedAtMs = 0L
        var unlockedSinceAccept = false
        var clearedSinceAccept = false

        /** 最近一次清桌上升沿的时间（毫秒，与 [commitAccepted] 同一时钟）；只在 [clearedSinceAccept] 为 true 时有意义。 */
        var clearedAtMs = 0L
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

    /**
     * 账本真正接受（Accepted）之后再更新门控状态，同时给已清桌的其他座位解锁。
     * [isStillCleared]：现场回查某区此刻是不是仍然清桌（手机和评测都传 [RoiPlayEventCache.isCleared]）。
     * 清桌标记是粘性的，旧牌被特效遮挡闪空后原样回来时标记还在，所以他座入账时必须再确认那一区此刻还空着，
     * 并且这次清桌已持续 [OTHER_SEAT_UNLOCK_MIN_CLEAR_MS] 以上，才解锁。故意不设默认值，调用方必须显式传入。
     */
    fun commitAccepted(
        zone: PlayZone,
        cards: RankCounts,
        timestampMs: Long,
        isStillCleared: (PlayZone) -> Boolean
    ) {
        if (cards.total() == 0) return
        val current = states.getValue(zone)
        current.accepted = cards
        current.acceptedAtMs = timestampMs
        current.unlockedSinceAccept = false
        current.clearedSinceAccept = false
        current.clearedAtMs = 0L
        if (zone.source == PlaySource.OUTSIDE) {
            states.forEach { (otherZone, otherState) ->
                if (otherZone != zone && otherZone.source == PlaySource.OUTSIDE &&
                    otherState.accepted != null && otherState.clearedSinceAccept &&
                    timestampMs - otherState.clearedAtMs >= OTHER_SEAT_UNLOCK_MIN_CLEAR_MS &&
                    isStillCleared(otherZone)
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
                commitAccepted(zone, cards, timestampMs) { true }
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
     * [timestampMs] 是这次清桌上升沿的时间（与 [commitAccepted] 同一时钟）；重复清桌以最近一次为准。
     */
    fun noteZoneCleared(zone: PlayZone, timestampMs: Long) {
        val state = states.getValue(zone)
        if (state.accepted == null) return
        state.clearedSinceAccept = true
        state.clearedAtMs = timestampMs
    }

    /** 人工撤销某座位的一手出牌后，清空该区上次入账记忆，避免错误记忆继续拦截后续出牌。 */
    fun noteReverted(seat: OutsideSeat) {
        PlayZone.entries.filter { it.seat == seat }.forEach { zone ->
            states.getValue(zone).apply {
                accepted = null
                acceptedAtMs = 0L
                unlockedSinceAccept = false
                clearedSinceAccept = false
                clearedAtMs = 0L
            }
        }
    }

    fun reset() {
        states.values.forEach {
            it.accepted = null
            it.acceptedAtMs = 0L
            it.unlockedSinceAccept = false
            it.clearedSinceAccept = false
            it.clearedAtMs = 0L
        }
    }

    companion object {
        /**
         * 子集/超集拦截只在上次入账后这么久内生效：用来拦同一手牌画面闪一下后补全或漏牌的重认
         * （重新停稳要 15 次采样约 0.75 秒，加识别耗时，1.5 秒内）。超出就放行：旧牌没收走、
         * 挂王没人要时直接盖上同主点数（或纯配牌）的新一手，不能被永久当成重认拦掉。
         */
        const val SUBSET_WINDOW_MS = 1_500L

        /**
         * 他座入账解锁本区所需的最短清桌时长：本区最近一次清桌上升沿到他座入账至少隔这么久，才算旧牌真的收走。
         * 压暗面板、特效等瞬时遮挡通常不到半秒，旧牌随后原样重现，不能当作新的一手。
         */
        const val OTHER_SEAT_UNLOCK_MIN_CLEAR_MS = 600L
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

/** 出牌区可见性三态：看到牌面、确认空桌、被压暗面板等遮挡（不推进清桌）。 */
enum class ZoneVisibility { CARD, EMPTY, OCCLUDED }

/**
 * 在 1280×578 画布坐标下采样判断单个出牌区的三态可见性：
 * - OUTSIDE 区采样范围与识别用的 [PlayZone.yoloCrop] 完全一致，并要求局部背景有绿桌（[hasLocalTableGreen]）；
 *   若局部绿桌不足 12%（如「王 3 / 送 3」压暗面板盖住出牌区），返回 [ZoneVisibility.OCCLUDED]，不推进清桌计数。
 * - SELF 区保持原来的判定带（[PlayZone.rankY] 往下 [PlayZone.visibilityHeight]），不查局部绿桌，返回 CARD 或 EMPTY。
 */
fun zoneVisibility(
    zone: PlayZone,
    step: Int = PLAY_VISIBILITY_STEP,
    pixel: (canonicalX: Int, canonicalY: Int) -> Int
): ZoneVisibility {
    val needsLocalGreen = zone.source == PlaySource.OUTSIDE
    val left: Int
    val right: Int
    val top: Int
    val bottom: Int
    if (needsLocalGreen) {
        val crop = zone.yoloCrop(ScreenScale.CANVAS_WIDTH, ScreenScale.CANVAS_HEIGHT)
        left = crop.left
        right = crop.left + crop.width - 1
        top = crop.top
        bottom = crop.top + crop.height - 1
    } else {
        left = zone.left(ScreenScale.CANVAS_WIDTH)
        right = zone.right(ScreenScale.CANVAS_WIDTH)
        top = zone.rankY(ScreenScale.CANVAS_HEIGHT)
        bottom = top + zone.visibilityHeight
    }
    var matched = 0
    var greenMatched = 0
    var samples = 0
    var y = top
    while (y <= bottom) {
        var x = left
        while (x <= right) {
            val rgb = pixel(x, y)
            if (isCardSurfaceRgb(rgb)) {
                matched++
            } else if (needsLocalGreen &&
                isDimmedOrNormalTableGreen((rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff)
            ) {
                greenMatched++
            }
            samples++
            x += step
        }
        y += step
    }
    if (needsLocalGreen && !hasLocalTableGreen(greenMatched, samples)) {
        return ZoneVisibility.OCCLUDED
    }
    return if (hasVisibleCardArea(matched, samples)) ZoneVisibility.CARD else ZoneVisibility.EMPTY
}

/** 直接从 [Raster] 采样判断六个出牌区的三态可见性；判定与 [zoneVisibility] 完全相同。 */
fun playZoneStates(frame: Raster, step: Int = PLAY_VISIBILITY_STEP): Map<PlayZone, ZoneVisibility> =
    PlayZone.entries.associateWith { zone ->
        zoneVisibility(zone, step) { x, y ->
            val sx = (x * frame.width / ScreenScale.CANVAS_WIDTH).coerceIn(0, frame.width - 1)
            val sy = (y * frame.height / ScreenScale.CANVAS_HEIGHT).coerceIn(0, frame.height - 1)
            frame.rgb(sx, sy)
        }
    }

/** 兼容布尔调用：仅当三态为 [ZoneVisibility.CARD] 时返回 true。 */
fun isZoneCardVisible(
    zone: PlayZone,
    step: Int = PLAY_VISIBILITY_STEP,
    pixel: (canonicalX: Int, canonicalY: Int) -> Int
): Boolean = zoneVisibility(zone, step, pixel) == ZoneVisibility.CARD

/** 直接从 [Raster] 采样判断六个出牌区是否可见牌面；仅当三态为 [ZoneVisibility.CARD] 时为 true。 */
fun playZoneVisibility(frame: Raster, step: Int = PLAY_VISIBILITY_STEP): Map<PlayZone, Boolean> =
    playZoneStates(frame, step).mapValues { (_, state) -> state == ZoneVisibility.CARD }

/** 牌桌绿点判定（1280×578 画布坐标，四周 7 点安全绿布投票，至少命中 3 个视为可见）。 */
fun isTableVisible(frame: Raster): Boolean =
    isTableVisible { x, y -> isTablePointGreen(frame, x, y) }

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

/**
 * 牌桌绿点判定：实测 dev 2929 帧可见牌桌第 3 绿探针点的 green - red 均 >= 50；
 * 半透明通知栏盖住牌桌时绿度被灰白底冲淡（green - red <= 46），收紧到 50 直接判牌桌不可见，防止误推清桌计时。
 */
fun isTableGreen(red: Int, green: Int, blue: Int): Boolean =
    green >= 70 && green - red >= 50 && green - blue >= 10

/** 宽松绿桌：兼容烧牌/开点 0.55 倍压暗后的桌面（绿色通道仍在 48~72）。压暗面板（如 3, 18, 15）不算。 */
fun isDimmedOrNormalTableGreen(red: Int, green: Int, blue: Int): Boolean =
    green >= 35 && green - red >= 10 && green - blue >= 5

/** 出牌区局部绿桌占比 >= 12%：正常 OUTSIDE 区 6 张满排也有 27.8% 以上，压暗面板盖住时为 0%。 */
fun hasLocalTableGreen(greenSamples: Int, totalSamples: Int): Boolean =
    totalSamples > 0 && greenSamples * 100 >= totalSamples * 12

/** 7 个探针点分布在牌桌四周安全绿布上，避开中央特效动画、所有悬浮窗和出牌区，至少命中 3 个即视为牌桌可见。 */
val TABLE_GREEN_PROBES: List<Pair<Int, Int>> = listOf(
    30 to 250,
    30 to 420,
    150 to 420,
    460 to 75,
    830 to 130,
    1120 to 420,
    1200 to 420
)

const val TABLE_GREEN_MIN_HITS: Int = 3

fun isTableVisible(isGreenAt: (x: Int, y: Int) -> Boolean): Boolean =
    TABLE_GREEN_PROBES.count { (x, y) -> isGreenAt(x, y) } >= TABLE_GREEN_MIN_HITS

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
        zoneStates: Map<PlayZone, ZoneVisibility>
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
        val outsideZones = PlayZone.entries.filter { it.source == PlaySource.OUTSIDE }
        val anyCard = outsideZones.any { zoneStates[it] == ZoneVisibility.CARD }
        if (anyCard) {
            consecutiveOutsideVisible++
            if (consecutiveOutsideVisible >= requiredConsecutive) {
                pending = false
                return MidGameCheckResult.SKIP
            }
            return MidGameCheckResult.PENDING
        }
        val allEmpty = outsideZones.all { zoneStates[it] == ZoneVisibility.EMPTY }
        if (allEmpty) {
            pending = false
            consecutiveOutsideVisible = 0
            return MidGameCheckResult.CLEAR
        }
        // 存在 OCCLUDED（压暗面板等遮挡）且没有 CARD：不推进有牌计数，也不当成“全空桌”提前结束检查。
        consecutiveOutsideVisible = 0
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
