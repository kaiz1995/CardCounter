package com.example.cardcounter.domain

object GameReducer {
    fun newSession(event: GameEvent.NewSession = GameEvent.NewSession()): GameState =
        GameState(phase = SessionPhase.PRE_PLAY, events = listOf(event)).also {
            it.requireInvariant()
        }

    /**
     * 派发单个事件。真·程序错误（phase 非法、未锁手等）抛 IllegalArgumentException；
     * 领域性拒绝（重复、数量与 Rank 不一致、超库存、守恒失败）返回结果对象，不抛异常。
     */
    fun apply(state: GameState, event: GameEvent): DispatchResult {
        if (state.events.any { it.id == event.id }) {
            return DispatchResult.DuplicateIgnored(state)
        }
        return when (event) {
            is GameEvent.NewSession -> accept(newSession(event))
            is GameEvent.SelfHandLocked -> lockHand(state, event)
            is GameEvent.OutsideCardsPlayed -> addOutsidePlay(state, event)
            is GameEvent.SelfCardsPlayed -> addSelfPlay(state, event)
            is GameEvent.Reverted -> revert(state, event)
            is GameEvent.SessionPaused -> pause(state, event)
            is GameEvent.SessionResumed -> resume(state, event)
            is GameEvent.SessionClosed -> accept(close(state, event))
        }
    }

    /**
     * 按事件日志回放。重复事件与拒绝类条件跳过（不再把会话打成 corrupted），
     * 守恒失败等暂停事件原样恢复，重启不自动解除暂停。
     */
    fun replay(events: List<GameEvent>): GameState {
        if (events.isEmpty() || events.first() !is GameEvent.NewSession) {
            return corrupted(events)
        }
        var state = newSession(events.first() as GameEvent.NewSession)
        for (event in events.drop(1)) {
            val result = runCatching { apply(state, event) }.getOrNull() ?: continue
            when (result) {
                is DispatchResult.Rejected, is DispatchResult.DuplicateIgnored -> Unit
                else -> state = result.state
            }
        }
        return state
    }

    private fun accept(state: GameState): DispatchResult {
        state.requireInvariant()
        return DispatchResult.Accepted(state)
    }

    private fun lockHand(state: GameState, event: GameEvent.SelfHandLocked): DispatchResult {
        require(state.phase == SessionPhase.PRE_PLAY) { "当前不能锁定手牌" }
        require(event.cardCount > 0) { "自己手牌不能为空" }
        require(event.cardCount <= Rank.initialInventory.total()) { "自己手牌超过固定库存" }
        if (event.rankCompleteness == RankCompleteness.UNKNOWN && event.cards.total() != 0) {
            return DispatchResult.Rejected(
                state,
                "UNKNOWN 锁手必须携带全零 Rank 向量，实际 total=${event.cards.total()}"
            )
        }
        // PARTIAL/UNKNOWN 锁手降级为数量账场景：Rank 账本永久不再可信。
        val next = when (event.rankCompleteness) {
            RankCompleteness.UNKNOWN -> state.copy(
                phase = SessionPhase.ACTIVE,
                selfLockedHand = null,
                selfLockedHandCount = event.cardCount,
                rankLedgerComplete = false,
                events = state.events + event
            )
            RankCompleteness.PARTIAL -> state.copy(
                phase = SessionPhase.ACTIVE,
                selfLockedHand = event.cards,
                selfLockedHandCount = event.cardCount,
                rankLedgerComplete = false,
                events = state.events + event
            )
            RankCompleteness.COMPLETE -> state.copy(
                phase = SessionPhase.ACTIVE,
                selfLockedHand = event.cards,
                selfLockedHandCount = event.cardCount,
                events = state.events + event
            )
        }
        return accept(next)
    }

    private fun addOutsidePlay(state: GameState, event: GameEvent.OutsideCardsPlayed): DispatchResult {
        require(state.phase == SessionPhase.ACTIVE) { "当前不接受自动出牌事件" }
        require(event.cardCount > 0) { "出牌张数必须为正数" }
        if (event.rankCompleteness == RankCompleteness.COMPLETE) {
            if (event.cardCount != event.cards.total()) {
                return DispatchResult.Rejected(
                    state,
                    "数量账与 Rank 账不一致: cardCount=${event.cardCount}, cards.total()=${event.cards.total()}"
                )
            }
            val locked = state.selfLockedHand
            val overRank = Rank.entries.firstOrNull { rank ->
                state.outsidePlayed[rank] + event.cards[rank] >
                    Rank.initialInventory[rank] - (locked?.get(rank) ?: 0)
            }
            if (overRank != null) {
                return pauseForConservation(state)
            }
            if (!withinConservation(state, event.cardCount)) {
                return pauseForConservation(state)
            }
            val bySeat = event.seat?.let { seat ->
                state.outsidePlayedBySeat + (seat to state.playedBy(seat).plus(event.cards))
            } ?: state.outsidePlayedBySeat
            val countBySeat = event.seat?.let { seat ->
                state.outsidePlayedCountBySeat + (seat to state.playedCountBy(seat) + event.cardCount)
            } ?: state.outsidePlayedCountBySeat
            return accept(
                state.copy(
                    outsidePlayed = state.outsidePlayed.plus(event.cards),
                    outsidePlayedCount = state.outsidePlayedCount + event.cardCount,
                    outsidePlayedBySeat = bySeat,
                    outsidePlayedCountBySeat = countBySeat,
                    events = state.events + event
                )
            )
        }
        // PARTIAL/UNKNOWN：只加数量账，不动 Rank 账本，并永久降级 rankLedgerComplete。
        if (!withinConservation(state, event.cardCount)) {
            return pauseForConservation(state)
        }
        val countBySeat = event.seat?.let { seat ->
            state.outsidePlayedCountBySeat + (seat to state.playedCountBy(seat) + event.cardCount)
        } ?: state.outsidePlayedCountBySeat
        val next = state.copy(
            outsidePlayedCount = state.outsidePlayedCount + event.cardCount,
            outsidePlayedCountBySeat = countBySeat,
            rankLedgerComplete = false,
            events = state.events + event
        )
        next.requireInvariant()
        return DispatchResult.AcceptedQuantityOnly(next)
    }

    private fun addSelfPlay(state: GameState, event: GameEvent.SelfCardsPlayed): DispatchResult {
        require(state.phase == SessionPhase.ACTIVE) { "当前不接受自己出牌事件" }
        require(event.cardCount > 0) { "出牌张数必须为正数" }
        val locked = requireNotNull(state.selfLockedHandCount) { "未锁定自己手牌" }
        if (event.rankCompleteness == RankCompleteness.COMPLETE &&
            event.cardCount != event.cards.total()
        ) {
            return DispatchResult.Rejected(
                state,
                "数量账与 Rank 账不一致: cardCount=${event.cardCount}, cards.total()=${event.cards.total()}"
            )
        }
        if (state.selfPlayedCount + event.cardCount > locked) {
            return pauseForConservation(state)
        }
        val next = state.copy(
            selfPlayedCount = state.selfPlayedCount + event.cardCount,
            rankLedgerComplete =
                if (event.rankCompleteness == RankCompleteness.COMPLETE) state.rankLedgerComplete
                else false,
            events = state.events + event
        )
        next.requireInvariant()
        return if (event.rankCompleteness == RankCompleteness.COMPLETE) {
            DispatchResult.Accepted(next)
        } else {
            DispatchResult.AcceptedQuantityOnly(next)
        }
    }

    private fun revert(state: GameState, event: GameEvent.Reverted): DispatchResult {
        require(state.phase == SessionPhase.ACTIVE || state.phase == SessionPhase.PAUSED) {
            "当前不能撤销"
        }
        val target = state.events.filterIsInstance<GameEvent.OutsideCardsPlayed>()
            .lastOrNull { it.id == event.targetEventId }
            ?: return DispatchResult.Rejected(state, "只能撤销已接受的外面出牌")
        if (target.id in state.revertedEventIds) {
            return DispatchResult.Rejected(state, "事件已撤销: ${target.id}")
        }
        // 只有 COMPLETE 事件进过 Rank 账本；数量账事件只需回退数量。
        val rankUndo = if (target.rankCompleteness == RankCompleteness.COMPLETE) {
            target.cards
        } else {
            RankCounts.zero()
        }
        val bySeat = target.seat?.let { seat ->
            if (target.rankCompleteness == RankCompleteness.COMPLETE) {
                state.outsidePlayedBySeat + (seat to state.playedBy(seat).minus(target.cards))
            } else {
                state.outsidePlayedBySeat
            }
        } ?: state.outsidePlayedBySeat
        val countBySeat = target.seat?.let { seat ->
            state.outsidePlayedCountBySeat + (seat to state.playedCountBy(seat) - target.cardCount)
        } ?: state.outsidePlayedCountBySeat
        return accept(
            state.copy(
                outsidePlayed = state.outsidePlayed.minus(rankUndo),
                outsidePlayedCount = state.outsidePlayedCount - target.cardCount,
                outsidePlayedBySeat = bySeat,
                outsidePlayedCountBySeat = countBySeat,
                revertedEventIds = state.revertedEventIds + target.id,
                events = state.events + event
            )
        )
    }

    private fun pause(state: GameState, event: GameEvent.SessionPaused): DispatchResult {
        require(state.phase == SessionPhase.PRE_PLAY || state.phase == SessionPhase.ACTIVE) {
            "当前不能暂停"
        }
        return accept(
            state.copy(
                phase = SessionPhase.PAUSED,
                pauseReason = event.reason,
                events = state.events + event
            )
        )
    }

    /**
     * 显式恢复：仅 PAUSED → ACTIVE，清空暂停原因并追加 SessionResumed 事件。
     * PRE_PLAY/ACTIVE/CLOSED 收到 Resume 一律 Rejected；
     * 重启回放不会产生 Resume 事件，暂停状态仍保留。
     */
    private fun resume(state: GameState, event: GameEvent.SessionResumed): DispatchResult {
        if (state.phase != SessionPhase.PAUSED) {
            return DispatchResult.Rejected(state, "仅 PAUSED 状态可恢复，当前 ${state.phase}")
        }
        if (state.events.isEmpty() || state.events.first() !is GameEvent.NewSession) {
            return DispatchResult.Rejected(state, "会话已损坏，不能恢复")
        }
        return accept(
            state.copy(
                phase = SessionPhase.ACTIVE,
                pauseReason = null,
                events = state.events + event
            )
        )
    }

    private fun close(state: GameState, event: GameEvent.SessionClosed): GameState =
        state.copy(phase = SessionPhase.CLOSED, events = state.events + event)

    private fun withinConservation(state: GameState, outsideAdded: Int): Boolean {
        val locked = state.selfLockedHandCount ?: return false
        return state.outsidePlayedCount + outsideAdded + locked <= Rank.initialInventory.total()
    }

    /** 守恒失败：追加暂停事件并暂停会话，触发守恒失败的原始事件不进 events。 */
    private fun pauseForConservation(state: GameState): DispatchResult {
        val paused = state.copy(
            phase = SessionPhase.PAUSED,
            pauseReason = PauseReason.CONSERVATION_FAILURE,
            events = state.events + GameEvent.SessionPaused(PauseReason.CONSERVATION_FAILURE)
        )
        paused.requireInvariant()
        return DispatchResult.Paused(paused, PauseReason.CONSERVATION_FAILURE)
    }

    private fun corrupted(events: List<GameEvent>): GameState = GameState(
        phase = SessionPhase.PAUSED,
        pauseReason = PauseReason.CORRUPTED_SESSION,
        events = events
    )
}
