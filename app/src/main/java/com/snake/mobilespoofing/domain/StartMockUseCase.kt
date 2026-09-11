package com.snake.mobilespoofing.domain

import android.content.Context
import com.snake.mobilespoofing.core.geo.MovementSpec
import com.snake.mobilespoofing.core.location.SessionConfigStore
import com.snake.mobilespoofing.service.MockLocationService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StartMockUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionConfigStore: SessionConfigStore,
) {
    /**
     * Stores the spec, then starts the service. Order matters: the service reads the store as soon
     * as it starts, and both live in this process.
     */
    operator fun invoke(spec: MovementSpec) {
        sessionConfigStore.set(spec)
        context.startForegroundService(MockLocationService.startIntent(context))
    }
}
