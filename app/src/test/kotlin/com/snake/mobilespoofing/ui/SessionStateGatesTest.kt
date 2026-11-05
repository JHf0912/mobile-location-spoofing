package com.snake.mobilespoofing.ui

import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint
import com.snake.mobilespoofing.core.model.SessionDegradedReason
import com.snake.mobilespoofing.core.model.SessionErrorReason
import com.snake.mobilespoofing.core.model.SessionState
import com.snake.mobilespoofing.core.model.canStartNewSession
import com.snake.mobilespoofing.core.model.isSessionActive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure guards shared by the UI, service, use cases and tile (audit F1/F4).
 *
 * A live phase and the non-terminal teardown-timeout window both refuse a start; only a live phase
 * offers Stop. These are the same predicates the service/use case call sites use, so this covers
 * the decision without Robolectric.
 */
class SessionStateGatesTest {

    private val point = GeoPoint(39.9042, 116.4074, CoordType.WGS84)

    private fun activeStates(): List<SessionState> = listOf(
        SessionState.Starting(sessionId = 1L, startedAtElapsedMs = 0L),
        SessionState.Running(sessionId = 1L, sessionEpochElapsedMs = 0L, lastPointWgs84 = point),
        SessionState.Degraded(
            sessionId = 1L,
            sessionEpochElapsedMs = 0L,
            lastPointWgs84 = point,
            reason = SessionDegradedReason.PushRejected,
            consecutiveFailures = 1,
        ),
        SessionState.Stopping(sessionId = 1L, sessionEpochElapsedMs = 0L),
    )

    @Test
    fun `idle and a terminal error allow a new session`() {
        assertTrue(SessionState.Idle.canStartNewSession)
        assertFalse(SessionState.Idle.isSessionActive)

        val terminal = SessionState.Error(
            sessionId = 1L,
            reason = SessionErrorReason.EngineStartFailed,
            terminal = true,
        )
        assertTrue(terminal.canStartNewSession)
        assertFalse(terminal.isSessionActive)
    }

    @Test
    fun `a live session blocks a new start and offers stop`() {
        activeStates().forEach { state ->
            assertTrue("${state.phase} must be active", state.isSessionActive)
            assertFalse("${state.phase} must block start", state.canStartNewSession)
        }
    }

    @Test
    fun `a non-terminal teardown timeout blocks start and offers no stop`() {
        val state = SessionState.Error(
            sessionId = 1L,
            reason = SessionErrorReason.TeardownTimeout,
            terminal = false,
        )

        assertFalse(state.isSessionActive)
        assertFalse(state.canStartNewSession)
    }
}
