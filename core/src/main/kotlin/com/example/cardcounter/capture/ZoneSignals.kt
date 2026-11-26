package com.example.cardcounter.capture

/**
 * 一个出牌区一次采样得到的“轮到他/清桌”信号：
 * - [clearedRose] 本区清桌刚刚从“没清”变成“清了”（上升沿）；空桌期间不会每个采样都为 true；
 * - [turned] 圆标连续帧确认轮到这一家；
 * - [longCleared] 连续空桌满 1.5 秒。
 */
data class ZoneSignals(val clearedRose: Boolean, val turned: Boolean, val longCleared: Boolean) {
    val any: Boolean get() = clearedRose || turned || longCleared
}

/**
 * 手机和电脑评测共用的出牌区信号跟踪：每个外面区、每次 50ms 采样都要调用 [sample]，不管牌桌看不看得见：
 * 牌桌被遮挡或切屏时空桌计时和圆标连续计数要归零，回来后不能把遮挡时长当成长清桌。
 * 圆标只在牌桌可见时才算（[badgeShown] 与牌桌可见取与）；圆标检测本身仍只在牌桌可见时才做。
 */
class ZoneSignalTracker(
    private val turns: TurnTracker = TurnTracker(),
    private val clearEdge: ZoneClearEdge = ZoneClearEdge()
) {
    fun sample(
        zone: PlayZone,
        badgeShown: Boolean,
        cleared: Boolean,
        zoneEmpty: Boolean,
        tableVisible: Boolean,
        nowMs: Long
    ): ZoneSignals {
        val rose = clearEdge.rose(zone, cleared)
        val update = turns.track(
            zone,
            shown = tableVisible && badgeShown,
            zoneEmpty = zoneEmpty,
            timestampMs = nowMs,
            tableVisible = tableVisible
        )
        return ZoneSignals(rose, update.turned, update.longCleared)
    }

    fun reset() {
        turns.reset()
        clearEdge.reset()
    }
}

/**
 * 把一个出牌区这次采样的信号交给去重门和开局门（手机在识别线程上调用，电脑评测在回放里直接调用）。
 * 顺序固定：清桌 → 轮到 → 长清桌。返回开局门因圆标打开时要补记的几手（没有就是空列表）；
 * 调用方对它们走正常入账流程（去重门、规则校验照常）。
 */
fun routeZoneSignals(
    zone: PlayZone,
    signals: ZoneSignals,
    nowMs: Long,
    stableGate: StablePlayGate,
    openingGate: OpeningPlayGate,
    isOnTable: (PlayZone) -> Boolean
): List<OpeningPlayGate.SkippedPlay> {
    var retries = emptyList<OpeningPlayGate.SkippedPlay>()
    if (signals.clearedRose) {
        stableGate.noteZoneCleared(zone, nowMs)
        openingGate.noteZoneCleared(zone)
    }
    if (signals.turned) {
        stableGate.noteTurn(zone)
        retries = openingGate.onOutsideTurn(turned = true, nowMs = nowMs, isOnTable = isOnTable)
    }
    if (signals.longCleared) stableGate.noteLongClear(zone)
    return retries
}

/**
 * 重置本局识别跟踪的三个独立开关，手机端 [resetSessionTracking] 按它重置：
 * - [primeExistingTable]：桌上已有的牌当作上一局残留的旧牌，不记；
 * - [checkMidOpen]：牌桌第一次出现时判断是不是中途打开；
 * - [alreadyInPlay]：本局已经进入正式出牌阶段（接着上次记）；新局不是，换牌期（交贡、还贡、买牌）要继续门控。
 * “忽略上一局残留牌”和“已进入出牌阶段”是两回事，不能共用一个参数。
 */
data class SessionTrackingReset(
    val primeExistingTable: Boolean,
    val checkMidOpen: Boolean,
    val alreadyInPlay: Boolean
) {
    companion object {
        /** 启动截屏：账本里已有手牌或外面出牌 = 接着记（[resumed]），否则是全新一局。 */
        fun onCaptureStart(resumed: Boolean) = SessionTrackingReset(
            primeExistingTable = resumed,
            checkMidOpen = !resumed,
            alreadyInPlay = resumed
        )

        /** 用户手动开新局：忽略桌上残留，不当作中途打开，且换牌期重新门控。 */
        fun onManualNewGame() = SessionTrackingReset(
            primeExistingTable = true,
            checkMidOpen = false,
            alreadyInPlay = false
        )
    }
}
