package com.snake.mobilespoofing.ui

import com.snake.mobilespoofing.core.geo.EndBehavior
import com.snake.mobilespoofing.core.geo.MovementSpec
import com.snake.mobilespoofing.core.model.SessionState
import com.snake.mobilespoofing.core.model.isSessionActive

/**
 * Builds the runnable spec for the current editor state, or null when the selection is not
 * runnable (no static point, or a route with fewer than [MainUiState.MIN_ROUTE_WAYPOINTS]).
 *
 * Pure and top-level so both the start path and the idle-spec sync use exactly the same rules.
 */
internal fun buildMovementSpec(state: MainUiState): MovementSpec? = when (state.mode) {
    SimulationMode.STATIC -> state.selectedPoint?.let(MovementSpec::Static)

    SimulationMode.ROUTE ->
        if (state.routeWaypoints.size >= MainUiState.MIN_ROUTE_WAYPOINTS) {
            MovementSpec.RouteSpec(
                waypointsWgs84 = state.routeWaypoints,
                speedMetersPerSecond = state.travelSpeed.metersPerSecond,
                endBehavior = state.endBehavior,
                closedLoop = state.endBehavior == EndBehavior.LOOP,
            )
        } else {
            null
        }
}

/** What an editor change should do to the pending session spec. */
internal sealed interface PendingSpecDecision {
    /** A session owns its spec already; the editor must not rewrite it. */
    data object Keep : PendingSpecDecision

    data class Update(val spec: MovementSpec) : PendingSpecDecision

    /** Nothing runnable is selected, so a stale spec must not survive until the next start. */
    data object Clear : PendingSpecDecision
}

/**
 * While a session is live, the service has already captured its spec; syncing would only change
 * what a later start (for example from the Quick Settings tile) runs. When idle, keep the pending
 * spec equal to what the editor shows so a tile restart cannot reuse an abandoned selection.
 *
 * [session] must be the authoritative phase from `MockStatusStore`, not the ViewModel's collected
 * copy: an edit dispatched just after a start can arrive before the UI state has observed
 * `Starting`, and the store is the only source that already knows the session is live.
 */
internal fun pendingSpecDecision(state: MainUiState, session: SessionState): PendingSpecDecision =
    if (session.isSessionActive) {
        PendingSpecDecision.Keep
    } else {
        buildMovementSpec(state)?.let(PendingSpecDecision::Update) ?: PendingSpecDecision.Clear
    }

/**
 * The session collector syncs on every observed inactive phase, not only on an active-to-inactive
 * transition. `StateFlow` deduplicates equal values, so a transition into an already-current Idle
 * can be conflated; checking the phase on every emission means a missed transition cannot leave
 * the pending spec stuck on the finished session's request. Active phases still Keep.
 */
internal fun shouldSyncPendingSpecForObservedSession(session: SessionState): Boolean =
    !session.isSessionActive
