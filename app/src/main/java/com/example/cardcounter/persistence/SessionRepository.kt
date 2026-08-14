package com.example.cardcounter.persistence

import android.content.Context
import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameState

class SessionRepository(context: Context) {
    private val store = SessionStore(context.applicationContext)

    var state: GameState = store.migratePositiveCountSessionIfNeeded()
        ?: store.load()
        ?: store.replaceWithNewSession()
        private set

    fun dispatch(event: GameEvent): GameState {
        state = store.apply(event)
        return state
    }

    fun newGame(): GameState {
        state = store.replaceWithNewSession()
        return state
    }
}
