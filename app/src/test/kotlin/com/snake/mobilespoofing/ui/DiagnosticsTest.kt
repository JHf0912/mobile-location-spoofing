package com.snake.mobilespoofing.ui

import com.snake.mobilespoofing.R
import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint
import com.snake.mobilespoofing.core.model.SessionDegradedReason
import com.snake.mobilespoofing.core.model.SessionErrorReason
import com.snake.mobilespoofing.core.model.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure derivation behind the live-diagnostics card.
 *
 * The card reads only [com.snake.mobilespoofing.core.location.MockPermissionChecker] (via `canMock`)
 * and [com.snake.mobilespoofing.core.location.MockStatusStore] (via [SessionState]); these tests lock
 * the tone and failure-reason mapping without Robolectric.
 */
class DiagnosticsTest {

    private val point = GeoPoint(39.9042, 116.4074, CoordType.WGS84)

    @Test
    fun `mock app row mirrors canMock`() {
        val accepted = deriveLiveDiagnostics(canMock = true, session = SessionState.Idle)
        val refused = deriveLiveDiagnostics(canMock = false, session = SessionState.Idle)

        assertTrue(accepted.mockAppSelected)
        assertFalse(refused.mockAppSelected)
    }

    @Test
    fun `running is the only good tone`() {
        val running = SessionState.Running(sessionId = 1L, sessionEpochElapsedMs = 0L, lastPointWgs84 = point)

        assertEquals(DiagnosticTone.Good, sessionTone(running))
        assertEquals(R.string.diag_session_running, sessionStatusRes(running))
        assertNull(lastFailureRes(running))
    }

    @Test
    fun `degraded is a warning that carries its push reason`() {
        val degraded = SessionState.Degraded(
            sessionId = 1L,
            sessionEpochElapsedMs = 0L,
            lastPointWgs84 = point,
            reason = SessionDegradedReason.PushRejected,
            consecutiveFailures = 1,
        )

        assertEquals(DiagnosticTone.Warning, sessionTone(degraded))
        assertEquals(R.string.diag_session_degraded, sessionStatusRes(degraded))
        assertEquals(R.string.reason_degraded_push_rejected, lastFailureRes(degraded))
    }

    @Test
    fun `a terminal error is bad and carries its reason`() {
        val error = SessionState.Error(
            sessionId = 1L,
            reason = SessionErrorReason.EngineStartFailed,
            terminal = true,
        )

        assertEquals(DiagnosticTone.Bad, sessionTone(error))
        assertEquals(R.string.diag_session_failed, sessionStatusRes(error))
        assertEquals(R.string.reason_error_engine_start_failed, lastFailureRes(error))
    }

    @Test
    fun `a non-terminal teardown timeout is bad and carries its reason`() {
        val error = SessionState.Error(
            sessionId = 1L,
            reason = SessionErrorReason.TeardownTimeout,
            terminal = false,
        )

        assertEquals(DiagnosticTone.Bad, sessionTone(error))
        assertEquals(R.string.reason_error_teardown_timeout, lastFailureRes(error))
    }

    @Test
    fun `idle, starting and stopping are neutral`() {
        assertEquals(DiagnosticTone.Neutral, sessionTone(SessionState.Idle))
        assertEquals(DiagnosticTone.Neutral, sessionTone(SessionState.Starting(sessionId = 1L, startedAtElapsedMs = 0L)))
        assertEquals(
            DiagnosticTone.Neutral,
            sessionTone(SessionState.Stopping(sessionId = 1L, sessionEpochElapsedMs = 0L)),
        )
    }

    @Test
    fun `non-failing states have no last failure`() {
        val states = listOf(
            SessionState.Idle,
            SessionState.Starting(sessionId = 1L, startedAtElapsedMs = 0L),
            SessionState.Running(sessionId = 1L, sessionEpochElapsedMs = 0L, lastPointWgs84 = point),
            SessionState.Stopping(sessionId = 1L, sessionEpochElapsedMs = 0L),
        )

        states.forEach { state ->
            assertNull("${state.phase} must have no failure reason", lastFailureRes(state))
        }
    }
}
