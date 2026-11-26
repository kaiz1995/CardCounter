package com.example.cardcounter.capture

import com.example.cardcounter.domain.Rank
import com.example.cardcounter.domain.RankCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出牌区去重门、开局门和信号跟踪的守卫测试：每个用例对应合并前审查报出的一条真实问题，撤掉对应改动就会失败。
 */
class ZoneGateGuardTest {
    private fun counts(vararg pairs: Pair<Rank, Int>) = RankCounts.of(mapOf(*pairs))

    private val king = counts(Rank.KING to 1)
    private val ace = counts(Rank.ACE to 1)

    // ---- 第 5 条：子集/超集拦截只在入账后的短时间窗内 ----

    @Test
    fun subsetSupersetOnlyBlocksInsideTheShortWindowAfterAccept() {
        val gate = StablePlayGate()
        val twoFours = counts(Rank.FOUR to 2)
        val threeFours = counts(Rank.FOUR to 3)
        gate.commitAccepted(PlayZone.TOP, twoFours, 10_000L) { true }

        assertEquals(
            PlayGateDecision.Suppressed(PlaySuppressionReason.SUBSET_OR_SUPERSET),
            gate.check(PlayZone.TOP, threeFours, 10_000L + StablePlayGate.SUBSET_WINDOW_MS)
        )
        // 超出时间窗：挂王没人要、旧牌没收走就盖牌续出同主点数的新一手，必须放行。
        assertTrue(gate.check(PlayZone.TOP, threeFours, 10_000L + StablePlayGate.SUBSET_WINDOW_MS + 1) is PlayGateDecision.Accept)
        // 完全相同的牌不受时间窗限制，仍然拦。
        assertEquals(
            PlayGateDecision.Suppressed(PlaySuppressionReason.EXACT_DUPLICATE),
            gate.check(PlayZone.TOP, twoFours, 10_000L + 60_000L)
        )
    }

    @Test
    fun pureWildFollowUpAfterTheWindowIsANewPlay() {
        val gate = StablePlayGate()
        val oneJoker = counts(Rank.BIG_JOKER to 1)
        val twoJokers = counts(Rank.BIG_JOKER to 1, Rank.SMALL_JOKER to 1)
        gate.commitAccepted(PlayZone.LEFT_TOP, oneJoker, 0L) { true }
        assertTrue(gate.check(PlayZone.LEFT_TOP, twoJokers, StablePlayGate.SUBSET_WINDOW_MS + 1) is PlayGateDecision.Accept)
    }

    // ---- 第 6 条：他座入账 + 本区短清桌不能绕过 1.5 秒长清桌防护 ----

    @Test
    fun otherSeatAcceptedBeforeOwnClearDoesNotUnlock() {
        val gate = StablePlayGate()
        gate.commitAccepted(PlayZone.TOP, king, 0L) { true }
        // 他座先入账（本区的牌还没清走），之后本区被特效遮挡闪空一下，只算已清桌，不能解锁。
        gate.commitAccepted(PlayZone.RIGHT_TOP, ace, 100L) { true }
        gate.noteZoneCleared(PlayZone.TOP, 200L)
        assertEquals(
            PlayGateDecision.Suppressed(PlaySuppressionReason.EXACT_DUPLICATE),
            gate.check(PlayZone.TOP, king, 5_000L)
        )
        // 本区清桌之后别家再入账，才解锁。
        gate.commitAccepted(PlayZone.LEFT_TOP, ace, 6_000L) { true }
        assertTrue(gate.check(PlayZone.TOP, king, 6_100L) is PlayGateDecision.Accept)
    }

    @Test
    fun otherSeatAcceptedAfterClearButCardsCameBackDoesNotUnlock() {
        val gate = StablePlayGate()
        gate.commitAccepted(PlayZone.TOP, king, 0L) { true }
        gate.noteZoneCleared(PlayZone.TOP, 100L)
        // 清桌标记还在，但入账那一刻本区的牌已经回来（isStillCleared = false）：同手牌仍判 EXACT_DUPLICATE。
        gate.commitAccepted(PlayZone.RIGHT_TOP, ace, 5_000L) { false }
        assertEquals(
            PlayGateDecision.Suppressed(PlaySuppressionReason.EXACT_DUPLICATE),
            gate.check(PlayZone.TOP, king, 5_100L)
        )
    }

    @Test
    fun otherSeatAcceptedJustAfterClearBelowMinimumDurationDoesNotUnlock() {
        val gate = StablePlayGate()
        gate.commitAccepted(PlayZone.TOP, king, 0L) { true }
        gate.noteZoneCleared(PlayZone.TOP, 1_000L)
        gate.commitAccepted(
            PlayZone.RIGHT_TOP, ace, 1_000L + StablePlayGate.OTHER_SEAT_UNLOCK_MIN_CLEAR_MS - 1
        ) { true }
        assertEquals(
            PlayGateDecision.Suppressed(PlaySuppressionReason.EXACT_DUPLICATE),
            gate.check(PlayZone.TOP, king, 2_000L)
        )
        gate.commitAccepted(
            PlayZone.LEFT_TOP, ace, 1_000L + StablePlayGate.OTHER_SEAT_UNLOCK_MIN_CLEAR_MS
        ) { true }
        assertTrue(gate.check(PlayZone.TOP, king, 2_100L) is PlayGateDecision.Accept)
    }

    @Test
    fun clearAloneNeverUnlocksButLongClearDoes() {
        val gate = StablePlayGate()
        gate.commitAccepted(PlayZone.TOP, king, 0L) { true }
        gate.noteZoneCleared(PlayZone.TOP, 100L)
        assertEquals(
            PlayGateDecision.Suppressed(PlaySuppressionReason.EXACT_DUPLICATE),
            gate.check(PlayZone.TOP, king, 5_000L)
        )
        gate.noteLongClear(PlayZone.TOP)
        assertTrue(gate.check(PlayZone.TOP, king, 5_100L) is PlayGateDecision.Accept)
    }

    @Test
    fun clearSignalFiresOnlyOnTheRisingEdgeNotEverySampleOfAnEmptyTable() {
        val tracker = ZoneSignalTracker()
        val rose = (0 until 12).map { i ->
            tracker.sample(PlayZone.TOP, badgeShown = false, cleared = i >= 3, zoneEmpty = false, tableVisible = true, nowMs = i * 50L)
                .clearedRose
        }
        assertEquals(1, rose.count { it })
        assertTrue(rose[3])
        // 清桌状态变回 false 之后，下一次清桌再次产生上升沿。
        tracker.sample(PlayZone.TOP, false, cleared = false, zoneEmpty = false, tableVisible = true, nowMs = 700L)
        assertTrue(tracker.sample(PlayZone.TOP, false, cleared = true, zoneEmpty = false, tableVisible = true, nowMs = 750L).clearedRose)
    }

    // ---- 第 4 条：牌桌被遮挡或切屏期间，空桌计时和圆标连续计数要归零 ----

    @Test
    fun hiddenTableResetsTheEmptyTimerSoOcclusionIsNotALongClear() {
        val tracker = ZoneSignalTracker()
        for (ms in 0..1000 step 50) {
            tracker.sample(PlayZone.TOP, false, cleared = false, zoneEmpty = true, tableVisible = true, nowMs = ms.toLong())
        }
        // 牌桌被挡 5 秒：这段时间看不见，不算清桌。
        for (ms in 1050..6000 step 50) {
            assertFalse(tracker.sample(PlayZone.TOP, false, cleared = false, zoneEmpty = true, tableVisible = false, nowMs = ms.toLong()).any)
        }
        // 回来之后空桌要重新数满 1.5 秒，不能把遮挡时长算进去。
        val after = (6050..7000 step 50).map {
            tracker.sample(PlayZone.TOP, false, cleared = false, zoneEmpty = true, tableVisible = true, nowMs = it.toLong()).longCleared
        }
        assertFalse(after.any { it })
        assertTrue(tracker.sample(PlayZone.TOP, false, cleared = false, zoneEmpty = true, tableVisible = true, nowMs = 7_600L).longCleared)
    }

    @Test
    fun badgeNeverCountsWhileTheTableIsHiddenAndNeedsTwoVisibleSamples() {
        val tracker = ZoneSignalTracker()
        repeat(5) { i ->
            assertFalse(tracker.sample(PlayZone.RIGHT_BOTTOM, true, cleared = false, zoneEmpty = true, tableVisible = false, nowMs = i * 50L).turned)
        }
        // 单帧闪一下不算轮到。
        assertFalse(tracker.sample(PlayZone.RIGHT_BOTTOM, true, cleared = false, zoneEmpty = true, tableVisible = true, nowMs = 300L).turned)
        assertFalse(tracker.sample(PlayZone.RIGHT_BOTTOM, false, cleared = false, zoneEmpty = true, tableVisible = true, nowMs = 350L).turned)
        // 连续两帧才算。
        assertFalse(tracker.sample(PlayZone.RIGHT_BOTTOM, true, cleared = false, zoneEmpty = true, tableVisible = true, nowMs = 400L).turned)
        assertTrue(tracker.sample(PlayZone.RIGHT_BOTTOM, true, cleared = false, zoneEmpty = true, tableVisible = true, nowMs = 450L).turned)
    }

    // ---- 第 7 条：开局门 ----

    private fun newOpeningGate() = OpeningPlayGate().also { it.reset(alreadyInPlay = false) }

    @Test
    fun singleFrameBadgeFlickerDoesNotOpenTheOpeningGate() {
        val tracker = ZoneSignalTracker()
        val stable = StablePlayGate()
        val opening = newOpeningGate()
        fun feed(badge: Boolean, nowMs: Long) = routeZoneSignals(
            PlayZone.RIGHT_BOTTOM,
            tracker.sample(PlayZone.RIGHT_BOTTOM, badge, cleared = false, zoneEmpty = true, tableVisible = true, nowMs = nowMs),
            nowMs, stable, opening
        ) { false }

        feed(true, 0L)
        feed(false, 50L)
        assertFalse(opening.isReady())

        feed(true, 100L)
        feed(true, 150L)
        assertTrue(opening.isReady())
    }

    @Test
    fun firstPlaySkippedBeforeTheBadgeIsReplayedWhenTheNextSeatBadgeOpensTheGate() {
        val opening = newOpeningGate()
        opening.notePrePlaySkipped(PlayZone.RIGHT_BOTTOM, king, 1_000L)
        val retries = opening.onOutsideTurn(turned = true, nowMs = 5_000L) { it == PlayZone.RIGHT_BOTTOM }
        assertEquals(listOf(OpeningPlayGate.SkippedPlay(PlayZone.RIGHT_BOTTOM, king, 1_000L)), retries)
        // 门控已开，之后不会再补记。
        assertTrue(opening.onOutsideTurn(turned = true, nowMs = 5_100L) { true }.isEmpty())
    }

    @Test
    fun exchangeDisplayCardsThatLeftTheTableAreNotReplayed() {
        val opening = newOpeningGate()
        opening.notePrePlaySkipped(PlayZone.RIGHT_BOTTOM, king, 1_000L)
        // 已清桌：清桌上升沿走 routeZoneSignals，会让开局门忘掉这一区丢掉的那手。
        val tracker = ZoneSignalTracker()
        val stable = StablePlayGate()
        routeZoneSignals(
            PlayZone.RIGHT_BOTTOM,
            tracker.sample(PlayZone.RIGHT_BOTTOM, false, cleared = true, zoneEmpty = false, tableVisible = true, nowMs = 2_000L),
            2_000L, stable, opening
        ) { true }
        assertTrue(opening.onOutsideTurn(turned = true, nowMs = 3_000L) { true }.isEmpty())

        // 没清桌，但牌已经不在桌上，也不补记。
        val other = newOpeningGate()
        other.notePrePlaySkipped(PlayZone.TOP, king, 1_000L)
        assertTrue(other.onOutsideTurn(turned = true, nowMs = 3_000L) { false }.isEmpty())
    }

    @Test
    fun onlyPlaysHandedInWithinEightSecondsBeforeTheGateOpenedAreReplayed() {
        val opening = newOpeningGate()
        opening.notePrePlaySkipped(PlayZone.TOP, king, 0L)
        val retries = opening.onOutsideTurn(turned = true, nowMs = OpeningPlayGate.RETRY_WINDOW_MS) { true }
        assertEquals(1, retries.size)

        val late = newOpeningGate()
        late.notePrePlaySkipped(PlayZone.TOP, king, 0L)
        assertTrue(late.onOutsideTurn(turned = true, nowMs = OpeningPlayGate.RETRY_WINDOW_MS + 1) { true }.isEmpty())
    }

    @Test
    fun exchangeDisplayCardsAreNeverRecordedBeforeTheOutsideBadgeOpensTheGate() {
        // 换牌期（交贡、还贡、买三买四）桌上的展示牌：手牌认没认出来都不放行，只有圆标连续确认才放行。
        val opening = newOpeningGate()
        assertFalse(opening.isReady())
        opening.notePrePlaySkipped(PlayZone.RIGHT_BOTTOM, king, 1_000L)
        assertFalse(opening.isReady())
    }

    @Test
    fun skippedPlaysAreForgottenOnNewGame() {
        val opening = newOpeningGate()
        opening.notePrePlaySkipped(PlayZone.TOP, king, 0L)
        opening.reset(alreadyInPlay = false)
        assertTrue(opening.onOutsideTurn(turned = true, nowMs = 100L) { true }.isEmpty())
    }

    // ---- 第 1 条：新局的开局门控不能因忽略桌上残留牌而失效 ----

    @Test
    fun manualNewGameKeepsTheOpeningGateClosedWhileIgnoringLeftoverCards() {
        val reset = SessionTrackingReset.onManualNewGame()
        assertTrue(reset.primeExistingTable)
        assertFalse(reset.checkMidOpen)
        assertFalse(reset.alreadyInPlay)

        val opening = OpeningPlayGate()
        opening.reset(alreadyInPlay = reset.alreadyInPlay)
        assertFalse(opening.isReady())
    }

    @Test
    fun captureStartResumedGameIsAlreadyInPlayAndFreshGameIsNot() {
        val resumed = SessionTrackingReset.onCaptureStart(resumed = true)
        assertTrue(resumed.primeExistingTable)
        assertFalse(resumed.checkMidOpen)
        assertTrue(resumed.alreadyInPlay)

        val fresh = SessionTrackingReset.onCaptureStart(resumed = false)
        assertFalse(fresh.primeExistingTable)
        assertTrue(fresh.checkMidOpen)
        assertFalse(fresh.alreadyInPlay)
    }
}
