package com.snake.mobilespoofing.ui

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
 * Behavior of the two session gates on [MainUiState].
 *
 * [MainUiState.canStart] answers "is there a runnable selection?" and
 * [MainUiState.canStartSession] additionally requires the setup checklist (permission plus the
 * mock-location AppOp) and a session phase that has nothing to stop (Idle or a terminal Error).
 * [MainUiState.canStop] is true exactly while a session exists. Points held in state are WGS84.
 */
class MainUiStateTest {

    private val beijing = GeoPoint(39.9042, 116.4074, CoordType.WGS84)
    private val shanghai = GeoPoint(31.2304, 121.4737, CoordType.WGS84)
    private val guangzhou = GeoPoint(23.1291, 113.2644, CoordType.WGS84)

    private val running = SessionState.Running(
        sessionId = 1L,
        sessionEpochElapsedMs = 1_000L,
        lastPointWgs84 = beijing,
    )
    private val starting = SessionState.Starting(sessionId = 1L, startedAtElapsedMs = 0L)
    private val degraded = SessionState.Degraded(
        sessionId = 1L,
        sessionEpochElapsedMs = 1_000L,
        lastPointWgs84 = beijing,
        reason = SessionDegradedReason.PushRejected,
        consecutiveFailures = 2,
    )
    private val stopping = SessionState.Stopping(sessionId = 1L, sessionEpochElapsedMs = 1_000L)
    private val terminalError = SessionState.Error(
        sessionId = 1L,
        reason = SessionErrorReason.EngineStartFailed,
        terminal = true,
    )
    private val nonTerminalError = SessionState.Error(
        sessionId = null,
        reason = SessionErrorReason.Unknown,
        terminal = false,
    )
    private val nonTerminalTeardownTimeout = SessionState.Error(
        sessionId = 1L,
        reason = SessionErrorReason.TeardownTimeout,
        terminal = false,
    )

    private fun staticState(
        session: SessionState = SessionState.Idle,
        permissionGranted: Boolean = true,
        canMock: Boolean = true,
    ) = MainUiState(
        mode = SimulationMode.STATIC,
        selectedPoint = beijing,
        session = session,
        permissionGranted = permissionGranted,
        canMock = canMock,
    )

    private fun routeState(
        waypoints: List<GeoPoint>,
        session: SessionState = SessionState.Idle,
        permissionGranted: Boolean = true,
        canMock: Boolean = true,
    ) = MainUiState(
        mode = SimulationMode.ROUTE,
        routeWaypoints = waypoints,
        session = session,
        permissionGranted = permissionGranted,
        canMock = canMock,
    )

    @Test
    fun `defaults keep start disabled`() {
        val state = MainUiState()

        assertEquals(SimulationMode.STATIC, state.mode)
        assertEquals(SessionState.Idle, state.session)
        assertNull(state.selectedPoint)
        assertEquals(emptyList<GeoPoint>(), state.routeWaypoints)
        assertFalse(state.permissionGranted)
        assertFalse(state.canMock)
        assertFalse(state.batteryWhitelisted)
        assertFalse(state.notificationsGranted)
        assertFalse(state.canStart)
        assertFalse(state.canStartSession)
        assertFalse(state.canStop)
    }

    @Test
    fun `static mode without a selected point cannot start`() {
        val state = staticState().copy(selectedPoint = null)

        assertFalse(state.canStart)
        assertFalse(state.canStartSession)
    }

    @Test
    fun `static mode with a selected point can start`() {
        val state = MainUiState(selectedPoint = beijing)

        assertTrue(state.canStart)
        // The selection is runnable, but the setup checklist is still incomplete.
        assertFalse(state.canStartSession)
    }

    @Test
    fun `static mode ignores route waypoints when deciding whether it can start`() {
        val state = MainUiState(
            mode = SimulationMode.STATIC,
            routeWaypoints = listOf(beijing, shanghai),
        )

        assertFalse(state.canStart)
    }

    @Test
    fun `route mode with no waypoints cannot start`() {
        val state = MainUiState(mode = SimulationMode.ROUTE)

        assertFalse(state.canStart)
        assertFalse(state.canStartSession)
    }

    @Test
    fun `route mode with one waypoint cannot start`() {
        val state = routeState(listOf(beijing))

        assertFalse(state.canStart)
        assertFalse(state.canStartSession)
    }

    @Test
    fun `route mode with two waypoints can start`() {
        val state = MainUiState(mode = SimulationMode.ROUTE, routeWaypoints = listOf(beijing, shanghai))

        assertTrue(state.canStart)
    }

    @Test
    fun `route mode accepts more than two waypoints`() {
        val state = routeState(listOf(beijing, shanghai, guangzhou))

        assertTrue(state.canStart)
        assertTrue(state.canStartSession)
    }

    @Test
    fun `route mode ignores the selected point when deciding whether it can start`() {
        val state = MainUiState(
            mode = SimulationMode.ROUTE,
            selectedPoint = beijing,
            routeWaypoints = listOf(shanghai),
        )

        assertFalse(state.canStart)
    }

    @Test
    fun `session can start from idle with a point and both capabilities`() {
        val state = staticState()

        assertTrue(state.canStart)
        assertTrue(state.canStartSession)
        assertFalse(state.canStop)
    }

    @Test
    fun `session can start from a terminal error with a point and both capabilities`() {
        val state = staticState(session = terminalError)

        assertTrue(state.canStart)
        assertTrue(state.canStartSession)
        // A terminated session must not leave Stop permanently clickable.
        assertFalse(state.canStop)
    }

    @Test
    fun `session cannot start from a non-terminal error`() {
        val state = staticState(session = nonTerminalError)

        assertTrue(state.canStart)
        assertFalse(state.canStartSession)
        assertFalse(state.canStop)
    }

    @Test
    fun `a teardown-timeout error blocks start and stop until the cleanup resolves`() {
        val state = staticState(session = nonTerminalTeardownTimeout)

        assertTrue(state.canStart)
        // The providers may still be unregistering: a new session must wait for Idle.
        assertFalse(state.canStartSession)
        assertFalse(state.canStop)
    }

    @Test
    fun `session cannot start without the location permission`() {
        val state = staticState(permissionGranted = false)

        assertTrue(state.canStart)
        assertFalse(state.canStartSession)
    }

    @Test
    fun `session cannot start without the mock location app capability`() {
        val state = staticState(canMock = false)

        assertTrue(state.canStart)
        assertFalse(state.canStartSession)
    }

    @Test
    fun `session cannot start when both capabilities are missing`() {
        val state = staticState(permissionGranted = false, canMock = false)

        assertTrue(state.canStart)
        assertFalse(state.canStartSession)
    }

    @Test
    fun `session cannot start while a session is already running`() {
        val state = staticState(session = running)

        assertTrue(state.canStart)
        assertFalse(state.canStartSession)
        assertTrue(state.canStop)
    }

    @Test
    fun `a live session blocks a new start in every active phase`() {
        listOf(starting, running, degraded, stopping).forEach { session ->
            val state = staticState(session = session)

            assertFalse("start must be blocked in ${session.phase}", state.canStartSession)
            assertTrue("stop must be offered in ${session.phase}", state.canStop)
        }
    }

    @Test
    fun `route session with two waypoints and both capabilities can start`() {
        val state = routeState(listOf(beijing, shanghai))

        assertTrue(state.canStart)
        assertTrue(state.canStartSession)
    }

    @Test
    fun `route session cannot start while a session is already running`() {
        val state = routeState(listOf(beijing, shanghai), session = running)

        assertFalse(state.canStartSession)
        assertTrue(state.canStop)
    }

    @Test
    fun `message and favorites do not affect the start gate`() {
        val state = staticState().copy(message = "heading out")

        assertTrue(state.canStartSession)
        assertEquals("heading out", state.message)
    }

    @Test
    fun `a missing notification permission does not block a session`() {
        // The foreground service still runs without POST_NOTIFICATIONS; only the visible
        // notification and its Stop action are lost, which the setup checklist surfaces.
        val state = staticState().copy(notificationsGranted = false)

        assertTrue(state.canStartSession)
        assertFalse(state.notificationsGranted)
    }
}
