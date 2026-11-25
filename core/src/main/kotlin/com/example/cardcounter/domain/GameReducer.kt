package com.example.cardcounter.domain

object GameReducer {
    fun newSession(event: GameEvent.NewSession = GameEvent.NewSession()): GameState =
        GameState(phase = SessionPhase.ACTIVE, events = listOf(event)).also {
            it.requireInvariant()
        }

    /**
     * 派发单个事件。真·程序错误（phase 非法等）抛 IllegalArgumentException；
     * 领域性拒绝（重复、手牌为空、已确认再确认、超库存、守恒失败）返回结果对象，不抛异常。
     * 手牌点数合计和数出来的张数对不上不拒绝：以点数合计为准，张数只作记录。
     */
    fun apply(state: GameState, event: GameEvent): DispatchResult {
        if (state.events.any { it.id == event.id }) {
            return DispatchResult.DuplicateIgnored(state)
        }
        return when (event) {
            is GameEvent.NewSession -> accept(newSession(event))
            is GameEvent.HandConfirmed -> confirmHand(state, event)
            is GameEvent.OutsideCardsPlayed -> addOutsidePlay(state, event)
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

    /**
     * 确认自己的手牌：每局只能一次，以点数合计为准；handCount（牌边缘数出来的张数）只作记录，不参与校验，
     * 对得上对不上由手牌草稿在确认前提示。
     * 已记的外面出牌加上这手牌会让某个点数超库存 → 守恒失败暂停，确认事件不进账。
     */
    private fun confirmHand(state: GameState, event: GameEvent.HandConfirmed): DispatchResult {
        require(state.phase == SessionPhase.ACTIVE) { "当前不能确认手牌" }
        if (state.handConfirmed) {
            return DispatchResult.Rejected(state, "本局手牌已确认，不能修改")
        }
        val total = event.cards.total()
        if (total <= 0) {
            return DispatchResult.Rejected(state, "手牌不能为空")
        }
        // 手牌单个点数本身就超库存：错的一定是手牌，不是外面的账。拒绝，让用户把这个点数改回库存以内，不停记。
        val overInventory = Rank.entries.firstOrNull { event.cards[it] > Rank.initialInventory[it] }
        if (overInventory != null) {
            return DispatchResult.Rejected(
                state,
                "手牌点数超过库存: ${overInventory.label} 手牌=${event.cards[overInventory]}, " +
                    "库存=${Rank.initialInventory[overInventory]}"
            )
        }
        if (Rank.entries.any { event.cards[it] + state.outsidePlayed[it] > Rank.initialInventory[it] }) {
            return pauseForConservation(state)
        }
        return accept(state.copy(selfHand = event.cards, events = state.events + event))
    }

    /** 外面出牌：任一点数 外面累计 + 本手 > 库存 − 自己手牌（已确认才扣）→ 守恒失败暂停，本手不进账。 */
    private fun addOutsidePlay(state: GameState, event: GameEvent.OutsideCardsPlayed): DispatchResult {
        require(state.phase == SessionPhase.ACTIVE) { "当前不接受自动出牌事件" }
        if (event.cards.total() <= 0) {
            return DispatchResult.Rejected(state, "出牌张数必须为正数")
        }
        val own = state.selfHand ?: RankCounts.zero()
        val over = Rank.entries.any { rank ->
            state.outsidePlayed[rank] + event.cards[rank] > Rank.initialInventory[rank] - own[rank]
        }
        if (over) {
            return pauseForConservation(state)
        }
        return accept(
            state.copy(
                outsidePlayed = state.outsidePlayed.plus(event.cards),
                outsidePlayedBySeat = state.outsidePlayedBySeat +
                    (event.seat to state.playedBy(event.seat).plus(event.cards)),
                events = state.events + event
            )
        )
    }

    /** 只能撤销已接受的外面出牌；HandConfirmed 不能撤销。 */
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
        return accept(
            state.copy(
                outsidePlayed = state.outsidePlayed.minus(target.cards),
                outsidePlayedBySeat = state.outsidePlayedBySeat +
                    (target.seat to state.playedBy(target.seat).minus(target.cards)),
                revertedEventIds = state.revertedEventIds + target.id,
                events = state.events + event
            )
        )
    }

    private fun pause(state: GameState, event: GameEvent.SessionPaused): DispatchResult {
        require(state.phase == SessionPhase.ACTIVE) { "当前不能暂停" }
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
     * ACTIVE/CLOSED 收到 Resume 一律 Rejected；
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
