package com.snake.mobilespoofing.core.location

import com.snake.mobilespoofing.core.model.SessionState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single source of truth for session state, shared between the foreground service and the UI.
 *
 * The historical class name is kept, but the value is the phase-aware [SessionState]: every
 * consumer (UI, tile, notification, use cases) must derive from this one stream and must not infer
 * a phase or a timer from anything else.
 *
 * Must stay a singleton *within the process* for that sharing to work — two instances would leave
 * the UI reporting `Idle` while the service is injecting. Note that a second process would get its
 * own `SingletonComponent` and reproduce exactly that failure, so never give the service an
 * `android:process` attribute.
 */
@Singleton
class MockStatusStore @Inject constructor() {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    fun emit(session: SessionState) {
        _state.value = session
    }
}
