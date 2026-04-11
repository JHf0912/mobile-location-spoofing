package com.snake.mobilespoofing.domain

import android.content.Context
import com.snake.mobilespoofing.service.MockLocationService

class StopMockUseCase(private val context: Context) {
    operator fun invoke() {
        context.startService(MockLocationService.stopIntent(context))
    }
}
