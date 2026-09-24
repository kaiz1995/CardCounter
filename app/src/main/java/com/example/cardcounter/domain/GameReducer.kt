package com.example.cardcounter.domain

object GameReducer {
    fun newSession(event: GameEvent.NewSession = GameEvent.NewSession()): GameState =
        GameState(phase = SessionPhase.PRE_PLAY, events = listOf(event)).also {
            it.requireInvariant()
        }

    fun apply(state: GameState, event: GameEvent): GameState {
        require(event.id !in state.events.map { it.id }) { "重复事件" }
        val next = when (event) {
            is GameEvent.NewSession -> newSession(event)
            is GameEvent.SelfHandLocked -> lockHand(state, event)
            is GameEvent.OutsideCardsPlayed -> addOutsidePlay(state, event)
            is GameEvent.SelfCardsPlayed -> addSelfPlay(state, event)
            is GameEvent.Reverted -> revert(state, event)
            is GameEvent.SessionPaused -> pause(state, event)
            is GameEvent.SessionClosed -> close(state, event)
        }
        next.requireInvariant()
        return next
    }

    fun replay(events: List<GameEvent>): GameState {
        if (events.isEmpty() || events.first() !is GameEvent.NewSession) {
            return corrupted(events)
        }
        return try {
            events.drop(1).fold(newSession(events.first() as GameEvent.NewSession)) { state, event ->
                apply(state, event)
            }
        } catch (_: IllegalArgumentException) {
            corrupted(events)
        } catch (_: IllegalStateException) {
            corrupted(events)
        }
    }

    private fun lockHand(state: GameState, event: GameEvent.SelfHandLocked): GameState {
        require(state.phase == SessionPhase.PRE_PLAY) { "当前不能锁定手牌" }
        require(event.cardCount > 0) { "自己手牌不能为空" }
        require(event.cardCount <= Rank.initialInventory.total()) { "自己手牌超过固定库存" }
        return state.copy(
            phase = SessionPhase.ACTIVE,
            selfLockedHand = event.cards,
            selfLockedHandCount = event.cardCount,
            events = state.events + event
        )
    }

    private fun addOutsidePlay(state: GameState, event: GameEvent.OutsideCardsPlayed): GameState {
        require(state.phase == SessionPhase.ACTIVE) { "当前不接受自动出牌事件" }
        require(event.cardCount > 0) { "出牌张数必须为正数" }
        if (!withinConservation(state, outsideAdded = event.cardCount)) return pauseForConservation(state)
        val bySeat = event.seat?.let { seat ->
            state.outsidePlayedBySeat + (seat to state.playedBy(seat).plus(event.cards))
        } ?: state.outsidePlayedBySeat
        val countBySeat = event.seat?.let { seat ->
            state.outsidePlayedCountBySeat + (seat to state.playedCountBy(seat) + event.cardCount)
        } ?: state.outsidePlayedCountBySeat
        return state.copy(
            outsidePlayed = state.outsidePlayed.plus(event.cards),
            outsidePlayedCount = state.outsidePlayedCount + event.cardCount,
            outsidePlayedBySeat = bySeat,
            outsidePlayedCountBySeat = countBySeat,
            events = state.events + event
        )
    }

    private fun addSelfPlay(state: GameState, event: GameEvent.SelfCardsPlayed): GameState {
        require(state.phase == SessionPhase.ACTIVE) { "当前不接受自己出牌事件" }
        require(event.cardCount > 0) { "出牌张数必须为正数" }
        val locked = requireNotNull(state.selfLockedHandCount) { "未锁定自己手牌" }
        if (state.selfPlayedCount + event.cardCount > locked) return pauseForConservation(state)
        return state.copy(
            selfPlayedCount = state.selfPlayedCount + event.cardCount,
            events = state.events + event
        )
    }

    private fun revert(state: GameState, event: GameEvent.Reverted): GameState {
        require(state.phase == SessionPhase.ACTIVE || state.phase == SessionPhase.PAUSED) {
            "当前不能撤销"
        }
        val target = state.events.filterIsInstance<GameEvent.OutsideCardsPlayed>()
            .lastOrNull { it.id == event.targetEventId }
            ?: error("只能撤销已接受的外面出牌")
        require(target.id !in state.revertedEventIds) { "事件已撤销" }
        val bySeat = target.seat?.let { seat ->
            state.outsidePlayedBySeat + (seat to state.playedBy(seat).minus(target.cards))
        } ?: state.outsidePlayedBySeat
        val countBySeat = target.seat?.let { seat ->
            state.outsidePlayedCountBySeat + (seat to state.playedCountBy(seat) - target.cardCount)
        } ?: state.outsidePlayedCountBySeat
        return state.copy(
            outsidePlayed = state.outsidePlayed.minus(target.cards),
            outsidePlayedCount = state.outsidePlayedCount - target.cardCount,
            outsidePlayedBySeat = bySeat,
            outsidePlayedCountBySeat = countBySeat,
            revertedEventIds = state.revertedEventIds + target.id,
            events = state.events + event
        )
    }

    private fun pause(state: GameState, event: GameEvent.SessionPaused): GameState {
        require(state.phase == SessionPhase.PRE_PLAY || state.phase == SessionPhase.ACTIVE) {
            "当前不能暂停"
        }
        return state.copy(
            phase = SessionPhase.PAUSED,
            pauseReason = event.reason,
            events = state.events + event
        )
    }

    private fun close(state: GameState, event: GameEvent.SessionClosed): GameState =
        state.copy(phase = SessionPhase.CLOSED, events = state.events + event)

    /**
     * 外界出牌入账前的守恒校验，替代直接 clamp。
     *
     * 保护的物理不变量：自己手里锁定的 [GameState.selfLockedHandCount] 张牌已经归属自己，
     * 无论后续怎么出牌都不可能被外面打出去；因此外面累计出牌数存在硬上界
     * `198 - selfLockedHandCount`（198 张只会分布在「外面已出 / 自己手里 / 外面手里」三处）。
     *
     * 何时触发：当本次要入账的 [outsideAdded] 张加进去后超过该上界，说明外面某个座位
     * 被重复入账了（典型场景：同一手牌因牌面抖动/动画被识别两次），此时不做 clamp、
     * 直接转入 PAUSED + NEW_GAME_UNCLEAR，交回用户确认。
     *
     * 注意：这里只校验"外面出牌"这一侧，不掺入 [GameState.selfPlayedCount]。
     * 自己出牌是**从自己手牌转移到桌上弃牌堆**，仍然消耗在 198 张之内、且不会让外面出牌变多，
     * 因此自己出牌不占用外面出牌的额度。
     *
     * ⚠️ **更正（2026-09-24）**：早先这里写的是
     * `outsidePlayed + outsideAdded + selfPlayed + (locked - selfPlayed) <= 198`。
     * 那两个 `selfPlayed` 项**代数相消**，等价于本式——所以它并非"恒真失效/永不触发"的 bug，
     * 语义是正确的（曾一度被误判为 P0 缺陷，已用反证法澄清：把本式换回旧写法，
     * 全部守恒测试仍然通过）。
     *
     * 本次改动只是**去掉自相抵消的项 + 补注释**，属可读性重构，**不改变任何行为**。
     * 保留这个更正记录，避免后续再把它当成 bug 修一遍。
     */
    private fun withinConservation(state: GameState, outsideAdded: Int): Boolean {
        val locked = state.selfLockedHandCount ?: return false
        return state.outsidePlayedCount + outsideAdded <= Rank.initialInventory.total() - locked
    }

    private fun pauseForConservation(state: GameState): GameState = state.copy(
        phase = SessionPhase.PAUSED,
        pauseReason = PauseReason.NEW_GAME_UNCLEAR,
        events = state.events + GameEvent.SessionPaused(PauseReason.NEW_GAME_UNCLEAR)
    )

    private fun corrupted(events: List<GameEvent>): GameState = GameState(
        phase = SessionPhase.PAUSED,
        pauseReason = PauseReason.CORRUPTED_SESSION,
        events = events
    )
}
