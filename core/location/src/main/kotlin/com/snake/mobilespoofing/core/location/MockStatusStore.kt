package com.snake.mobilespoofing.core.location

import com.snake.mobilespoofing.core.model.MockStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single source of truth for session state, shared between the foreground service and the UI.
 *
 * Must be a singleton within the process for that sharing to work — two instances would leave the
 * UI reporting `Idle` while the service is injecting.
 */
class MockStatusStore {
    private val _status = MutableStateFlow<MockStatus>(MockStatus.Idle)
    val status: StateFlow<MockStatus> = _status.asStateFlow()

    fun emit(status: MockStatus) {
        _status.value = status
    }
}
