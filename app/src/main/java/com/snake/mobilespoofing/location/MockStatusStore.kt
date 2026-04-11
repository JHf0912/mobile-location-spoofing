package com.snake.mobilespoofing.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MockStatusStore {
    private val _status = MutableStateFlow<MockStatus>(MockStatus.Idle)
    val status: StateFlow<MockStatus> = _status.asStateFlow()

    fun emit(status: MockStatus) {
        _status.value = status
    }
}
