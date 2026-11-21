package com.snake.mobilespoofing.ui

import androidx.annotation.StringRes
import com.snake.mobilespoofing.R
import com.snake.mobilespoofing.core.model.SessionState
import com.snake.mobilespoofing.degradedReasonRes
import com.snake.mobilespoofing.errorReasonRes

/** Tone of a live-diagnostic row; drives the status dot colour. */
enum class DiagnosticTone { Good, Warning, Bad, Neutral }

/**
 * Content of the "live diagnostics" section, derived from the app's readable signals only.
 *
 * [mockAppSelected] comes from [com.snake.mobilespoofing.core.location.MockPermissionChecker]
 * (surfaced as [MainUiState.canMock]); [sessionStatusRes], [sessionTone] and [lastFailureRes] come
 * from the current [SessionState] (published by
 * [com.snake.mobilespoofing.core.location.MockStatusStore]). Nothing here reads root or Shizuku.
 */
data class LiveDiagnostics(
    val mockAppSelected: Boolean,
    val sessionStatusRes: Int,
    val sessionTone: DiagnosticTone,
    val lastFailureRes: Int?,
)

fun deriveLiveDiagnostics(canMock: Boolean, session: SessionState): LiveDiagnostics =
    LiveDiagnostics(
        mockAppSelected = canMock,
        sessionStatusRes = sessionStatusRes(session),
        sessionTone = sessionTone(session),
        lastFailureRes = lastFailureRes(session),
    )

/** The provider registration/push outcome for the current session phase. */
@StringRes
fun sessionStatusRes(session: SessionState): Int = when (session) {
    SessionState.Idle -> R.string.diag_session_idle
    is SessionState.Starting -> R.string.diag_session_starting
    is SessionState.Running -> R.string.diag_session_running
    is SessionState.Degraded -> R.string.diag_session_degraded
    is SessionState.Stopping -> R.string.diag_session_stopping
    is SessionState.Error -> R.string.diag_session_failed
}

fun sessionTone(session: SessionState): DiagnosticTone = when (session) {
    SessionState.Idle,
    is SessionState.Starting,
    is SessionState.Stopping,
    -> DiagnosticTone.Neutral

    is SessionState.Running -> DiagnosticTone.Good
    is SessionState.Degraded -> DiagnosticTone.Warning
    is SessionState.Error -> DiagnosticTone.Bad
}

/**
 * The failure reason of the current session, or null while it is not failing.
 *
 * Degraded is a warning (a live session that cannot inject reliably), not a failure, but its reason
 * is still the most useful explanation to show; an [SessionState.Error] is a hard failure.
 */
fun lastFailureRes(session: SessionState): Int? = when (session) {
    is SessionState.Degraded -> degradedReasonRes(session.reason)
    is SessionState.Error -> errorReasonRes(session.reason)
    else -> null
}
