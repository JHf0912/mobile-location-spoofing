package com.snake.mobilespoofing.domain

import android.content.Context
import com.snake.mobilespoofing.core.model.GeoPoint
import com.snake.mobilespoofing.service.MockLocationService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StartMockUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    operator fun invoke(point: GeoPoint) {
        context.startForegroundService(MockLocationService.startIntent(context, point))
    }
}
