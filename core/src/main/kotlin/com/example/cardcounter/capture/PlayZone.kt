package com.example.cardcounter.capture

import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.RankCounts
import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.OutsideSeat
import kotlin.math.roundToInt

/** 固定皮肤的六个公开出牌区；中央仅代表自己，其余五区代表外面。 */
enum class PlayZone(
    val source: PlaySource,
    val seat: OutsideSeat?,
    private val leftRatio: Double,
    private val rightRatio: Double,
    private val rankYRatio: Double,
    val visibilityHeight: Int = 70
) {
    TOP(PlaySource.OUTSIDE, OutsideSeat.TOP, 0.539, 0.602, 0.022),
    LEFT_TOP(PlaySource.OUTSIDE, OutsideSeat.LEFT_TOP, 0.148, 0.266, 0.156, 56),
    LEFT_BOTTOM(PlaySource.OUTSIDE, OutsideSeat.LEFT_BOTTOM, 0.141, 0.281, 0.429),
    RIGHT_TOP(PlaySource.OUTSIDE, OutsideSeat.RIGHT_TOP, 0.750, 0.859, 0.156, 56),
    RIGHT_BOTTOM(PlaySource.OUTSIDE, OutsideSeat.RIGHT_BOTTOM, 0.750, 0.859, 0.429),
    SELF(PlaySource.SELF, null, 0.438, 0.563, 0.358, 28);

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

/** 同一区连续两次相同候选才接收；清空两次后允许下一组相同牌再次出现。 */
class StablePlayGate {
    private data class ZoneState(
        val candidate: RankCounts? = null,
        val stableSamples: Int = 0,
        val emptySamples: Int = 0,
        val accepted: RankCounts? = null
    )

    private val states = PlayZone.entries.associateWith { ZoneState() }.toMutableMap()

    fun update(zone: PlayZone, cards: RankCounts): StablePlay? {
        val before = states.getValue(zone)
        val next = when {
            cards.total() == 0 -> before.copy(
                candidate = null,
                stableSamples = 0,
                emptySamples = before.emptySamples + 1,
                accepted = if (before.emptySamples + 1 >= CLEAR_SAMPLES) null else before.accepted
            )
            cards == before.candidate -> before.copy(
                stableSamples = before.stableSamples + 1,
                emptySamples = 0
            )
            else -> before.copy(candidate = cards, stableSamples = 1, emptySamples = 0)
        }
        states[zone] = next
        return if (next.stableSamples >= 2 && next.accepted != cards) {
            states[zone] = next.copy(accepted = cards)
            StablePlay(zone, cards)
        } else {
            null
        }
    }

    /** Caller already established a stable batch; apply only accepted-value deduplication. */
    fun acceptStable(zone: PlayZone, cards: RankCounts): StablePlay? {
        if (cards.total() == 0) return null
        val before = states.getValue(zone)
        if (before.accepted == cards) return null
        states[zone] = before.copy(candidate = cards, stableSamples = 0, emptySamples = 0, accepted = cards)
        return StablePlay(zone, cards)
    }

    fun reset() {
        PlayZone.entries.forEach { states[it] = ZoneState() }
    }

    private companion object {
        const val CLEAR_SAMPLES = 6
    }
}

/** YOLO会把“过牌”等文字误识别成牌，必须同时存在白色牌面。 */
fun isPlayVisible(whiteAreaVisible: Boolean): Boolean = whiteAreaVisible

/** 单张牌在最宽的侧边区域约占14.5%，阈值留到10%避免漏掉深色花纹。 */
fun hasVisibleCardArea(whiteSamples: Int, totalSamples: Int): Boolean =
    totalSamples > 0 && whiteSamples * 100 >= totalSamples * 10

fun isTableGreen(red: Int, green: Int, blue: Int): Boolean =
    green >= 70 && green - red >= 30 && green - blue >= 10

/** 公开出牌的点数位固定在裁剪中部；底部边缘框来自界面文字或面板。 */
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

/** 单张以全屏固定牌角模板为准；ROI 模板未校准，不能参与此处融合。 */
fun fuseSinglePublicPlay(zone: PlayZone, model: RankCounts, template: RankCounts): RankCounts =
    if (model.total() == 1 && template.total() == 1 &&
        !(zone == PlayZone.LEFT_BOTTOM && template[Rank.ACE] > 0 && model[Rank.ACE] == 0)
    ) template else model

fun shouldAuditAce(zone: PlayZone, cards: RankCounts): Boolean =
    zone == PlayZone.LEFT_BOTTOM && cards[com.example.cardcounter.domain.Rank.ACE] > 0
