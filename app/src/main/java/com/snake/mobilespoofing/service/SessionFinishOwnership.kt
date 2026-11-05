package com.snake.mobilespoofing.service

import com.snake.mobilespoofing.core.model.SessionErrorReason
import com.snake.mobilespoofing.core.model.SessionState

/**
 * Whether a completion callback for [finishedSessionId] still owns the published session.
 *
 * The runner checks that a session is current before invoking `onFinished`, but that check is not
 * atomic with the callback: a new session can start in between. The service re-reads the store at
 * the start of the finish path and must not cancel the notification/service that now belongs to a
 * different session.
 *
 * Idle and a matching terminal/non-terminal Error count as owned: the runner publishes the final
 * state before invoking the callback. An error without a session id (preflight failure) is owned as
 * well — it never carries a live session. So is any non-terminal TeardownTimeout error: it
 * describes a session that is already over, and the runner refuses every new start until that
 * cleanup completes, so it cannot be masking a live session.
 *
 * [startInFlight] closes the one remaining ambiguity: while a new start is in preflight the store
 * still reads Idle, so Idle alone does not prove the finishing callback owns the service.
 */
internal fun finishStillOwnsSession(
    finishedSessionId: Long,
    current: SessionState,
    startInFlight: Boolean,
): Boolean {
    if (startInFlight && current is SessionState.Idle) return false
    return when (current) {
        SessionState.Idle -> true
        is SessionState.Error ->
            current.sessionId == null ||
                current.sessionId == finishedSessionId ||
                (!current.terminal && current.reason == SessionErrorReason.TeardownTimeout)
        is SessionState.Starting -> current.sessionId == finishedSessionId
        is SessionState.Running -> current.sessionId == finishedSessionId
        is SessionState.Degraded -> current.sessionId == finishedSessionId
        is SessionState.Stopping -> current.sessionId == finishedSessionId
    }
}
