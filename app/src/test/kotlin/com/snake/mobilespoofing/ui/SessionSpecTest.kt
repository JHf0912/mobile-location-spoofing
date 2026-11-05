package com.snake.mobilespoofing.ui

import com.snake.mobilespoofing.core.geo.EndBehavior
import com.snake.mobilespoofing.core.geo.MovementSpec
import com.snake.mobilespoofing.core.geo.TravelSpeeds
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
 * The pending-spec sync keeps the Quick Settings tile and a later start aligned with the editor,
 * but only while no session owns the service. The authoritative phase is passed in explicitly
 * (the store), because the collected UI state can lag a just-dispatched transition.
 */
class SessionSpecTest {

    private val beijing = GeoPoint(39.9042, 116.4074, CoordType.WGS84)
    private val shanghai = GeoPoint(31.2304, 121.4737, CoordType.WGS84)

    private val starting = SessionState.Starting(sessionId = 1L, startedAtElapsedMs = 0L)
    private val running = SessionState.Running(
        sessionId = 1L,
        sessionEpochElapsedMs = 0L,
        lastPointWgs84 = beijing,
    )
    private val degraded = SessionState.Degraded(
        sessionId = 1L,
        sessionEpochElapsedMs = 0L,
        lastPointWgs84 = beijing,
        reason = SessionDegradedReason.PushRejected,
        consecutiveFailures = 1,
    )
    private val stopping = SessionState.Stopping(sessionId = 1L, sessionEpochElapsedMs = 0L)
    private val terminalError = SessionState.Error(
        sessionId = 1L,
        reason = SessionErrorReason.EngineStartFailed,
        terminal = true,
    )
    private val teardownTimeout = SessionState.Error(
        sessionId = 1L,
        reason = SessionErrorReason.TeardownTimeout,
        terminal = false,
    )

    @Test
    fun `idle static mode with a point publishes a static spec`() {
        val state = MainUiState(mode = SimulationMode.STATIC, selectedPoint = beijing)

        assertEquals(
            PendingSpecDecision.Update(MovementSpec.Static(beijing)),
            pendingSpecDecision(state, SessionState.Idle),
        )
    }

    @Test
    fun `idle static mode without a point clears the pending spec`() {
        assertEquals(
            PendingSpecDecision.Clear,
            pendingSpecDecision(MainUiState(mode = SimulationMode.STATIC), SessionState.Idle),
        )
    }

    @Test
    fun `a route with too few waypoints clears the pending spec`() {
        val state = MainUiState(
            mode = SimulationMode.ROUTE,
            routeWaypoints = listOf(beijing),
        )

        assertEquals(PendingSpecDecision.Clear, pendingSpecDecision(state, SessionState.Idle))
    }

    @Test
    fun `an idle route publishes speed and end behavior`() {
        val state = MainUiState(
            mode = SimulationMode.ROUTE,
            routeWaypoints = listOf(beijing, shanghai),
            travelSpeed = TravelSpeeds.CYCLING,
            endBehavior = EndBehavior.LOOP,
        )

        assertEquals(
            PendingSpecDecision.Update(
                MovementSpec.RouteSpec(
                    waypointsWgs84 = listOf(beijing, shanghai),
                    speedMetersPerSecond = TravelSpeeds.CYCLING.metersPerSecond,
                    endBehavior = EndBehavior.LOOP,
                    closedLoop = true,
                ),
            ),
            pendingSpecDecision(state, SessionState.Idle),
        )
    }

    @Test
    fun `a non-loop route is not closed`() {
        val state = MainUiState(
            mode = SimulationMode.ROUTE,
            routeWaypoints = listOf(beijing, shanghai),
            endBehavior = EndBehavior.STOP,
        )

        val decision = pendingSpecDecision(state, SessionState.Idle) as PendingSpecDecision.Update
        val spec = decision.spec as MovementSpec.RouteSpec
        assertEquals(false, spec.closedLoop)
    }

    @Test
    fun `a live session keeps the pending spec untouched in every active phase`() {
        listOf(starting, running, degraded, stopping).forEach { session ->
            val state = MainUiState(
                selectedPoint = beijing,
                routeWaypoints = listOf(beijing, shanghai),
                session = session,
            )
            assertEquals(
                "${session.phase} must not rewrite the spec",
                PendingSpecDecision.Keep,
                pendingSpecDecision(state, session),
            )
        }
    }

    @Test
    fun `an edit before the UI observes Starting still sees the authoritative active phase`() {
        // The tap that starts the session has not been collected into MainUiState yet, but the
        // store already holds Starting. The store phase must win.
        val laggingUi = MainUiState(selectedPoint = beijing, session = SessionState.Idle)

        assertEquals(PendingSpecDecision.Keep, pendingSpecDecision(laggingUi, starting))
    }

    @Test
    fun `a finished session resumes editor-to-spec syncing`() {
        val state = MainUiState(selectedPoint = beijing, session = terminalError)

        assertEquals(
            PendingSpecDecision.Update(MovementSpec.Static(beijing)),
            pendingSpecDecision(state, terminalError),
        )
    }

    @Test
    fun `a non-terminal teardown window still syncs the idle editor choice`() {
        val state = MainUiState(selectedPoint = null, session = teardownTimeout)

        // isSessionActive is false, so the editor is the source of truth; it is simply not runnable.
        assertEquals(PendingSpecDecision.Clear, pendingSpecDecision(state, teardownTimeout))
    }

    @Test
    fun `every observed inactive phase syncs and every active phase keeps`() {
        // StateFlow dedups equal values, so the collector must sync on any inactive observation
        // rather than only on an active-to-inactive transition.
        listOf(SessionState.Idle, terminalError, teardownTimeout).forEach { session ->
            assertTrue("${session.phase} must sync", shouldSyncPendingSpecForObservedSession(session))
        }
        listOf(starting, running, degraded, stopping).forEach { session ->
            assertFalse("${session.phase} must keep", shouldSyncPendingSpecForObservedSession(session))
        }
    }

    @Test
    fun `an explicit stop is not undone by the pre-stop spec`() {
        // The session ran the spec captured at start. The user edits during the session, then
        // stops: while Stopping the editor must not rewrite the store, and when Idle arrives the
        // store is pointed at the edited selection rather than the abandoned start spec.
        val abandoned = MainUiState(selectedPoint = beijing)
        val edited = MainUiState(selectedPoint = shanghai, session = stopping)

        assertEquals(PendingSpecDecision.Keep, pendingSpecDecision(edited, stopping))
        assertFalse(shouldSyncPendingSpecForObservedSession(stopping))

        assertTrue(shouldSyncPendingSpecForObservedSession(SessionState.Idle))
        assertEquals(
            PendingSpecDecision.Update(MovementSpec.Static(shanghai)),
            pendingSpecDecision(edited.copy(session = SessionState.Idle), SessionState.Idle),
        )
        // Sanity check that the abandoned start spec is not what gets re-synced.
        assertEquals(MovementSpec.Static(beijing), buildMovementSpec(abandoned))
    }

    @Test
    fun `a natural end re-syncs the editor the user sees`() {
        val editedDuringSession = MainUiState(
            mode = SimulationMode.ROUTE,
            routeWaypoints = listOf(beijing, shanghai),
            travelSpeed = TravelSpeeds.CYCLING,
            session = running,
        )

        assertTrue(shouldSyncPendingSpecForObservedSession(terminalError))
        assertEquals(
            PendingSpecDecision.Update(
                MovementSpec.RouteSpec(
                    waypointsWgs84 = listOf(beijing, shanghai),
                    speedMetersPerSecond = TravelSpeeds.CYCLING.metersPerSecond,
                    endBehavior = EndBehavior.STOP,
                    closedLoop = false,
                ),
            ),
            pendingSpecDecision(editedDuringSession.copy(session = terminalError), terminalError),
        )
    }

    @Test
    fun `buildMovementSpec matches canStart`() {
        val runnable = MainUiState(selectedPoint = beijing)
        val notRunnable = MainUiState()
        assertTrue(runnable.canStart)
        assertEquals(MovementSpec.Static(beijing), buildMovementSpec(runnable))
        assertNull(buildMovementSpec(notRunnable))
    }
}
