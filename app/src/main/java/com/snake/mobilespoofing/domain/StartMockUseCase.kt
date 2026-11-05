package com.snake.mobilespoofing.domain

import android.content.Context
import android.util.Log
import com.snake.mobilespoofing.core.geo.MovementSpec
import com.snake.mobilespoofing.core.location.MockStatusStore
import com.snake.mobilespoofing.core.location.SessionConfigStore
import com.snake.mobilespoofing.core.model.SessionErrorReason
import com.snake.mobilespoofing.core.model.SessionState
import com.snake.mobilespoofing.core.model.canStartNewSession
import com.snake.mobilespoofing.service.MockLocationService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StartMockUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionConfigStore: SessionConfigStore,
    private val statusStore: MockStatusStore,
) {
    /**
     * Stores the spec, then starts the service. Order matters: the service reads the store as soon
     * as it starts, and both live in this process.
     */
    operator fun invoke(spec: MovementSpec) {
        // A live session, and the non-terminal teardown-timeout window, refuse a second start
        // before any state is touched. The service re-checks as well; rejecting here also avoids
        // writing a new spec over the one an active session is using. The runner keeps its
        // sessionId/join fallback for direct callers.
        val current = statusStore.state.value
        if (!current.canStartNewSession) {
            Log.w(TAG, "ignoring start request: session phase is ${current.phase}")
            return
        }

        sessionConfigStore.set(spec)
        try {
            context.startForegroundService(MockLocationService.startIntent(context))
        } catch (e: Exception) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException when the process is not
            // allowed to start a foreground service; a revoked permission can also surface here.
            // This is called from the UI, so the process must not crash. Only report when there is
            // still nothing to protect: a Starting/Running/Degraded/Stopping session, or the
            // non-terminal teardown window, must not be relabelled as failed.
            Log.w(TAG, "could not start the mock service", e)
            if (statusStore.state.value.canStartNewSession) {
                statusStore.emit(
                    SessionState.Error(
                        sessionId = null,
                        reason = SessionErrorReason.ServiceStartFailed,
                        terminal = true,
                    ),
                )
            }
        }
    }

    private companion object {
        const val TAG = "StartMockUseCase"
    }
}
