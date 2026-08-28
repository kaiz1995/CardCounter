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

    /** 开局手牌快照 H0（任意张数，交贡/买3/买4 后、首手前锁定一次）。 */
    private fun lockHand(state: GameState, event: GameEvent.SelfHandLocked): GameState {
        require(state.phase == SessionPhase.PRE_PLAY) { "当前不能锁定手牌" }
        require(event.cards.total() > 0) { "自己手牌不能为空" }
        require(event.cards.fitsWithin(Rank.initialInventory)) { "自己手牌超过固定库存" }
        return state.copy(
            phase = SessionPhase.ACTIVE,
            selfLockedHand = event.cards,
            events = state.events + event
        )
    }

    private fun addOutsidePlay(state: GameState, event: GameEvent.OutsideCardsPlayed): GameState {
        require(state.phase == SessionPhase.ACTIVE) { "当前不接受自动出牌事件" }
        require(event.cardCount > 0) { "出牌张数必须为正数" }
        val remaining = state.outsideRemaining ?: error("未锁定自己手牌")
        require(event.cards.fitsWithin(remaining)) { "外面已出超过剩余库存" }
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
        require(event.cards.total() > 0) { "自己出牌张数必须为正数" }
        val handRemaining = state.selfLockedHand?.minus(state.selfPlayed)
            ?: error("未锁定自己手牌")
        require(event.cards.fitsWithin(handRemaining)) { "自己出牌超过剩余手牌" }
        return state.copy(
            selfPlayed = state.selfPlayed.plus(event.cards),
            selfPlayedCount = state.selfPlayedCount + event.cards.total(),
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
        require(state.phase == SessionPhase.ACTIVE) { "当前不能暂停" }
        return state.copy(
            phase = SessionPhase.PAUSED,
            pauseReason = event.reason,
            events = state.events + event
        )
    }

    private fun close(state: GameState, event: GameEvent.SessionClosed): GameState =
        state.copy(phase = SessionPhase.CLOSED, events = state.events + event)

    private fun corrupted(events: List<GameEvent>): GameState = GameState(
        phase = SessionPhase.PAUSED,
        pauseReason = PauseReason.CORRUPTED_SESSION,
        events = events
    )
}
