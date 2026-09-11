package com.snake.mobilespoofing.ui

import com.snake.mobilespoofing.core.geo.EndBehavior
import com.snake.mobilespoofing.core.geo.TravelSpeeds
import com.snake.mobilespoofing.core.model.FavoriteItem
import com.snake.mobilespoofing.core.model.GeoPoint
import com.snake.mobilespoofing.core.model.MockStatus

/** What tapping the map means right now. */
enum class SimulationMode { STATIC, ROUTE }

/**
 * All positions here are WGS84, converted on the way in from the map. AMap renders GCJ02, so the
 * conversion has to happen somewhere, and doing it at the boundary means nothing downstream has to
 * remember which datum it is holding.
 */
data class MainUiState(
    val mode: SimulationMode = SimulationMode.STATIC,
    val selectedPoint: GeoPoint? = null,
    val routeWaypoints: List<GeoPoint> = emptyList(),
    val travelSpeed: TravelSpeeds = TravelSpeeds.WALKING,
    val endBehavior: EndBehavior = EndBehavior.STOP,
    val status: MockStatus = MockStatus.Idle,
    val favorites: List<FavoriteItem> = emptyList(),
    val permissionGranted: Boolean = false,
    val canMock: Boolean = false,
    val message: String? = null,
) {
    val canStart: Boolean
        get() = when (mode) {
            SimulationMode.STATIC -> selectedPoint != null
            SimulationMode.ROUTE -> routeWaypoints.size >= MIN_ROUTE_WAYPOINTS
        }

    companion object {
        const val MIN_ROUTE_WAYPOINTS = 2
    }
}
