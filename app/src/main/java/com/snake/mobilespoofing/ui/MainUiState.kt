package com.snake.mobilespoofing.ui

import com.snake.mobilespoofing.core.geo.EndBehavior
import com.snake.mobilespoofing.core.geo.TravelSpeeds
import com.snake.mobilespoofing.core.model.FavoriteItem
import com.snake.mobilespoofing.core.model.GeoPoint
import com.snake.mobilespoofing.core.model.SessionState
import com.snake.mobilespoofing.core.model.canStartNewSession
import com.snake.mobilespoofing.core.model.isSessionActive

/** What tapping the map means right now. */
enum class SimulationMode { STATIC, ROUTE }

/**
 * All positions here are WGS84, converted on the way in from the map. AMap renders GCJ02, so the
 * conversion has to happen somewhere, and doing it at the boundary means nothing downstream has to
 * remember which datum it is holding.
 *
 * [session] is the single source of truth for the session phase, the epoch and the last injected
 * point. The UI never infers a timer or a phase of its own.
 */
data class MainUiState(
    val mode: SimulationMode = SimulationMode.STATIC,
    val selectedPoint: GeoPoint? = null,
    val routeWaypoints: List<GeoPoint> = emptyList(),
    val travelSpeed: TravelSpeeds = TravelSpeeds.WALKING,
    val endBehavior: EndBehavior = EndBehavior.STOP,
    val session: SessionState = SessionState.Idle,
    val favorites: List<FavoriteItem> = emptyList(),
    val permissionGranted: Boolean = false,
    val canMock: Boolean = false,
    val batteryWhitelisted: Boolean = false,
    /**
     * Whether the system lets the app post its foreground-service notification. Informational
     * only: a denied notification permission does not block a session (the service still runs and
     * the Quick Settings tile still stops it), so it is deliberately absent from [canStartSession].
     */
    val notificationsGranted: Boolean = false,
    val message: String? = null,
) {
    val canStart: Boolean
        get() = when (mode) {
            SimulationMode.STATIC -> selectedPoint != null
            SimulationMode.ROUTE -> routeWaypoints.size >= MIN_ROUTE_WAYPOINTS
        }

    /**
     * Starting while a session is already running restarts it from the beginning. The runner now
     * serialises that safely, but there is no reason to offer it: the user asked for one session.
     * Permission and the mock-app AppOp are part of the gate, so the button reflects the setup
     * checklist instead of only failing after the tap.
     *
     * Starting/Stopping/Running/Degraded all block a new start; a terminal Error does not, because
     * the session is over and the user must be able to retry. A *non-terminal* Error (stuck
     * teardown) also blocks: its providers may still be unregistering, so a new session would
     * overlap that cleanup. It resolves to Idle when the teardown completes.
     */
    val canStartSession: Boolean
        get() = canStart &&
            permissionGranted &&
            canMock &&
            session.canStartNewSession

    /**
     * Stop is offered exactly while a session exists. It is disabled for Idle and for any Error
     * (terminal or the teardown-timeout window), so "stopped" does not leave a permanently
     * clickable Stop button or invite a second stop that repeats the timeout.
     */
    val canStop: Boolean
        get() = session.isSessionActive

    companion object {
        const val MIN_ROUTE_WAYPOINTS = 2
    }
}
