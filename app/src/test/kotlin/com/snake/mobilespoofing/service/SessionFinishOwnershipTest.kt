package com.snake.mobilespoofing.service

import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint
import com.snake.mobilespoofing.core.model.SessionDegradedReason
import com.snake.mobilespoofing.core.model.SessionErrorReason
import com.snake.mobilespoofing.core.model.SessionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the finish-ownership decision used by [MockLocationService] (audit F6/G3).
 *
 * The runner checks that a session is current before invoking `onFinished`, but that check is not
 * atomic with the callback. The service re-reads the store and must ignore a completion whose id
 * no longer matches the published session, and must not treat Idle as owned while a new start is
 * already in preflight.
 */
class SessionFinishOwnershipTest {

    private val finishedSessionId = 7L
    private val point = GeoPoint(39.9042, 116.4074, CoordType.WGS84)

    @Test
    fun `idle and same-id states belong to the finished session`() {
        assertTrue(finishStillOwnsSession(finishedSessionId, SessionState.Idle, startInFlight = false))
        assertTrue(
            finishStillOwnsSession(
                finishedSessionId,
                SessionState.Error(finishedSessionId, SessionErrorReason.EngineStartFailed),
                startInFlight = false,
            ),
        )
        assertTrue(
            finishStillOwnsSession(
                finishedSessionId,
                SessionState.Error(finishedSessionId, SessionErrorReason.TeardownTimeout, terminal = false),
                startInFlight = false,
            ),
        )
        // Preflight failures carry no session; they never represent a newer live session.
        assertTrue(
            finishStillOwnsSession(
                finishedSessionId,
                SessionState.Error(null, SessionErrorReason.MissingSpec),
                startInFlight = false,
            ),
        )
        // A refused start reports the *previous* session id in the teardown-timeout window; it is
        // still the callback's service to tear down, never a newer live session.
        assertTrue(
            finishStillOwnsSession(
                finishedSessionId,
                SessionState.Error(1L, SessionErrorReason.TeardownTimeout, terminal = false),
                startInFlight = false,
            ),
        )
    }

    @Test
    fun `a newer session id is not torn down by an old completion`() {
        val newer = listOf(
            SessionState.Starting(sessionId = 8L, startedAtElapsedMs = 0L),
            SessionState.Running(sessionId = 8L, sessionEpochElapsedMs = 0L, lastPointWgs84 = point),
            SessionState.Degraded(
                sessionId = 8L,
                sessionEpochElapsedMs = 0L,
                lastPointWgs84 = point,
                reason = SessionDegradedReason.PushRejected,
                consecutiveFailures = 1,
            ),
            SessionState.Stopping(sessionId = 8L, sessionEpochElapsedMs = 0L),
            SessionState.Error(8L, SessionErrorReason.TooManyConsecutiveFailures),
        )

        newer.forEach { current ->
            assertFalse(
                "a completion for $finishedSessionId must not own ${current.phase}",
                finishStillOwnsSession(finishedSessionId, current, startInFlight = false),
            )
        }
    }

    @Test
    fun `idle is not owned while a new start window is in flight`() {
        // G3: handleStart has begun but has not published Starting yet, so the store still reads
        // Idle. The old completion must not cancel that new start.
        assertFalse(
            finishStillOwnsSession(finishedSessionId, SessionState.Idle, startInFlight = true),
        )
        // A published phase already disambiguates ownership, so the flag does not block a
        // legitimate finish of the same or a final state.
        assertTrue(
            finishStillOwnsSession(
                finishedSessionId,
                SessionState.Error(finishedSessionId, SessionErrorReason.TeardownTimeout, terminal = false),
                startInFlight = true,
            ),
        )
        assertFalse(
            finishStillOwnsSession(
                finishedSessionId,
                SessionState.Starting(sessionId = 8L, startedAtElapsedMs = 0L),
                startInFlight = true,
            ),
        )
    }
}
