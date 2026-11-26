package com.example.cardcounter.capture

import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.domain.OutsideSeat
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

/**
 * 去重门：每区记“上一次入账的牌”和“入账后有没有轮到过这一家”。
 * 认出的牌与上一手不同就是新的一手；相同时，只有入账后轮到过这一家才算新的一手，否则当作重复。
 * 清桌不清“上一次入账的牌”；只有 [reset]（新局）全清。
 */
class StablePlayGate {
    private class ZoneState {
        var accepted: RankCounts? = null
        var turnedSinceAccept = false
    }

    private val states = PlayZone.entries.associateWith { ZoneState() }

    /** 返回 null 表示空牌或重复，不入账。 */
    fun accept(zone: PlayZone, cards: RankCounts): StablePlay? {
        if (cards.total() == 0) return null
        val state = states.getValue(zone)
        if (state.accepted == cards && !state.turnedSinceAccept) return null
        state.accepted = cards
        state.turnedSinceAccept = false
        return StablePlay(zone, cards)
    }

    /** 这一区的玩家轮到过了（见 [TurnTracker]）：之后再出一样的牌也算新的一手。 */
    fun noteTurn(zone: PlayZone) {
        states.getValue(zone).turnedSinceAccept = true
    }

    fun reset() {
        states.values.forEach {
            it.accepted = null
            it.turnedSinceAccept = false
        }
    }
}

/** YOLO会把“过牌”等文字误识别成牌，必须同时存在白色牌面。 */
fun isPlayVisible(whiteAreaVisible: Boolean): Boolean = whiteAreaVisible

/** 单张牌在最宽的侧边区域约占14.5%，阈值留到10%避免漏掉深色花纹。 */
fun hasVisibleCardArea(whiteSamples: Int, totalSamples: Int): Boolean =
    totalSamples > 0 && whiteSamples * 100 >= totalSamples * 10

fun isTableGreen(red: Int, green: Int, blue: Int): Boolean =
    green >= 70 && green - red >= 30 && green - blue >= 10

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

/**
 * 中途打开不记本局：账本里还没有手牌和外面出牌（全新的一局），
 * 但牌桌上已经能看到公共出牌，说明这一局早就开始了，本局不记，等下一局。
 * 账本里已有记录的局是接着记，不在此列。
 */
fun shouldSkipOpenedGame(handConfirmed: Boolean, outsidePlayedCount: Int, visiblePlayZones: Int): Boolean =
    !handConfirmed && outsidePlayedCount == 0 && visiblePlayZones > 0

/** 多张且每张都很确定时不等待下一帧，避免开点动画清桌前丢失整手。 */
fun canFastConfirmPlay(cards: RankCounts, minimumConfidence: Float): Boolean =
    cards.total() >= 3 && minimumConfidence >= 0.60f
