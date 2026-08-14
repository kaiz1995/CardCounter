package com.example.cardcounter.persistence

import android.content.Context
import com.example.cardcounter.domain.GameEvent
import com.example.cardcounter.domain.GameReducer
import com.example.cardcounter.domain.GameState

class SessionRepository(context: Context) {
    private val store = SessionStore(context.applicationContext)

    var state: GameState = store.migratePositiveCountSessionIfNeeded()
        ?: store.load()
        ?: GameReducer.newSession().also(store::save)
        private set

    fun dispatch(event: GameEvent): GameState {
        state = GameReducer.apply(state, event)
        store.save(state)
        return state
    }

    fun newGame(): GameState {
        state = GameReducer.newSession()
        store.save(state)
        return state
    }
}
