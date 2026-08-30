package com.example.cardcounter.capture

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

internal data class StablePlay(val zone: PlayZone, val cards: RankCounts, val cardCount: Int = cards.total())

/** 同一区连续两次相同候选才接收；清空两次后允许下一组相同牌再次出现。 */
internal class StablePlayGate {
    private data class ZoneState(
        val candidate: RankCounts? = null,
        val stableSamples: Int = 0,
        val emptySamples: Int = 0,
        val accepted: RankCounts? = null,
        val lastAcceptedAtMs: Long = 0L
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

    /** Caller already established a stable batch; suppress only the same batch re-finalized within 500ms. */
    fun acceptStable(
        zone: PlayZone,
        cards: RankCounts,
        cardCount: Int = cards.total(),
        nowMs: Long = 0L
    ): StablePlay? {
        if (cards.total() == 0) return null
        require(cardCount > 0)
        val before = states.getValue(zone)
        if (before.accepted == cards && nowMs - before.lastAcceptedAtMs < SAME_BATCH_WINDOW_MS) return null
        states[zone] = before.copy(
            candidate = cards,
            stableSamples = 0,
            emptySamples = 0,
            accepted = cards,
            lastAcceptedAtMs = nowMs
        )
        return StablePlay(zone, cards, cardCount)
    }

    fun reset() {
        PlayZone.entries.forEach { states[it] = ZoneState() }
    }

    /** 服务重启时，桌面尚未收走的牌只作为去重基线。 */
    fun prime(zone: PlayZone, cards: RankCounts) {
        states[zone] = ZoneState(accepted = cards.takeIf { it.total() > 0 })
    }

    private companion object {
        const val CLEAR_SAMPLES = 6
        const val SAME_BATCH_WINDOW_MS = 500L
    }
}

/** SELF 区检测到的牌若与最近外部区入账牌完全一致且在时间窗内，判定为对家牌误入 SELF 区。 */
internal fun arbitrateSelfPlay(
    selfCards: RankCounts,
    recentOutside: Map<PlayZone, Pair<RankCounts, Long>>,
    nowMs: Long,
    windowMs: Long = 300
): PlayZone? = recentOutside.entries
    .firstOrNull { it.value.first == selfCards && nowMs - it.value.second <= windowMs }
    ?.key

internal fun stableUnknownCount(previous: Int, cardsVisible: Boolean, cards: RankCounts): Int = when {
    !cardsVisible || cards.total() > 0 -> 0
    else -> previous + 1
}

/** YOLO会把“过牌”等文字误识别成牌，必须同时存在白色牌面。 */
internal fun isPlayVisible(whiteAreaVisible: Boolean): Boolean = whiteAreaVisible

/** 单张牌在最宽的侧边区域约占14.5%，阈值留到10%避免漏掉深色花纹。 */
internal fun hasVisibleCardArea(whiteSamples: Int, totalSamples: Int): Boolean =
    totalSamples > 0 && whiteSamples * 100 >= totalSamples * 10

internal fun isTableGreen(red: Int, green: Int, blue: Int): Boolean =
    green >= 70 && green - red >= 30 && green - blue >= 10

/** 公开出牌的点数位固定在裁剪中部；底部边缘框来自界面文字或面板。 */
internal fun isPublicRankBand(centerY: Float): Boolean = centerY < 0.85f

/** 开局前必须先稳定手牌；正式对局时公共出牌不应被自己手牌动画阻塞。 */
internal fun shouldProbeRanks(firstPlayArmed: Boolean, stableHandSamples: Int): Boolean =
    firstPlayArmed || stableHandSamples >= 3

/** 3 张以上且整体高置信时不等待下一帧；1-2 张也允许高置信单帧确认，避免动画清桌前丢失。 */
internal fun canFastConfirmPlay(cards: RankCounts, minimumConfidence: Float): Boolean =
    (cards.total() >= 3 && minimumConfidence >= 0.60f) ||
        (cards.total() in 1..2 && minimumConfidence >= 0.85f)

/** 单张以全屏固定牌角模板为准；ROI 模板未校准，不能参与此处融合。 */
internal fun fuseSinglePublicPlay(model: RankCounts, template: RankCounts): RankCounts =
    if (model.total() == 1 && template.total() == 1) template else model

/** 牌桌动画会短暂露出无法识别的牌面；连续三次才按未知牌暂停。 */
internal fun isStableUnknownPlay(samples: Int): Boolean = samples >= 3
