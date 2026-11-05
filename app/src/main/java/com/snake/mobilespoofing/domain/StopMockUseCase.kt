package com.snake.mobilespoofing.domain

import android.content.Context
import android.util.Log
import com.snake.mobilespoofing.service.MockLocationService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StopMockUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    operator fun invoke() {
        // startService rather than startForegroundService: this is reached from the UI, and the
        // service is already running as a foreground service by then. Using the foreground variant
        // here would oblige the service to call startForeground() within five seconds even on a
        // path that is meant to shut it down.
        try {
            context.startService(MockLocationService.stopIntent(context))
        } catch (e: Exception) {
            // Log only. The stop never reached the service, so the published phase is still the
            // truth: do not turn Idle into a start-oriented Error, and never overwrite an active
            // session (Starting/Running/Degraded/Stopping). The notification action and the QS
            // tile remain stop paths.
            Log.w(TAG, "could not send stop to the mock service", e)
        }
    }

    private companion object {
        const val TAG = "StopMockUseCase"
    }
}
