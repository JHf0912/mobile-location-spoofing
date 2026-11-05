package com.snake.mobilespoofing.ui

import androidx.lifecycle.SavedStateHandle
import com.snake.mobilespoofing.core.geo.EndBehavior
import com.snake.mobilespoofing.core.geo.TravelSpeeds
import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint

/**
 * The map-editor fields that must survive process death.
 *
 * Only editor choices live here. The session phase, favorites and capability flags are re-read
 * from their own sources after a restart, so persisting them would only risk restoring stale
 * copies.
 */
internal data class EditorSnapshot(
    val mode: SimulationMode = SimulationMode.STATIC,
    val selectedPoint: GeoPoint? = null,
    val routeWaypoints: List<GeoPoint> = emptyList(),
    val travelSpeed: TravelSpeeds = TravelSpeeds.WALKING,
    val endBehavior: EndBehavior = EndBehavior.STOP,
) {
    companion object {
        /**
         * Saved state travels through a Binder transaction (about 1 MB for the whole Bundle), so
         * the route is capped. The live editor enforces the same cap, which keeps the route on
         * screen and the route restored after process death identical instead of silently
         * truncating a longer route.
         */
        const val MAX_ROUTE_WAYPOINTS = 500
    }
}

internal fun MainUiState.toEditorSnapshot(): EditorSnapshot = EditorSnapshot(
    mode = mode,
    selectedPoint = selectedPoint,
    routeWaypoints = routeWaypoints,
    travelSpeed = travelSpeed,
    endBehavior = endBehavior,
)

internal fun EditorSnapshot.applyTo(state: MainUiState): MainUiState = state.copy(
    mode = mode,
    selectedPoint = selectedPoint,
    routeWaypoints = routeWaypoints,
    travelSpeed = travelSpeed,
    endBehavior = endBehavior,
)

/** Outcome of adding a point to a route under the saved-state cap. */
internal sealed interface RouteAppendResult {
    data class Appended(val waypoints: List<GeoPoint>) : RouteAppendResult

    /** The route is full; the point was not added. */
    data object Full : RouteAppendResult
}

/**
 * Adds [point] unless the route already holds [EditorSnapshot.MAX_ROUTE_WAYPOINTS] waypoints.
 *
 * The coordinate entry treats [RouteAppendResult.Full] as a refusal (it reports the cap and keeps
 * the typed text); the map/favorite paths keep the pre-existing behavior of ignoring the extra
 * point, since they have no text field to explain the cap in.
 */
internal fun appendRouteWaypoint(
    waypoints: List<GeoPoint>,
    point: GeoPoint,
): RouteAppendResult =
    if (waypoints.size >= EditorSnapshot.MAX_ROUTE_WAYPOINTS) {
        RouteAppendResult.Full
    } else {
        RouteAppendResult.Appended(waypoints + point)
    }

/** Writes the editor fields as enum names, doubles and a flat coordinate array. */
internal fun EditorSnapshot.writeTo(handle: SavedStateHandle) {
    handle[EditorStateKeys.MODE] = mode.name
    handle[EditorStateKeys.TRAVEL_SPEED] = travelSpeed.name
    handle[EditorStateKeys.END_BEHAVIOR] = endBehavior.name

    val point = selectedPoint
    if (point == null) {
        handle.remove<Double>(EditorStateKeys.SELECTED_LATITUDE)
        handle.remove<Double>(EditorStateKeys.SELECTED_LONGITUDE)
    } else {
        handle[EditorStateKeys.SELECTED_LATITUDE] = point.latitude
        handle[EditorStateKeys.SELECTED_LONGITUDE] = point.longitude
    }

    handle[EditorStateKeys.ROUTE] = encodeRouteWaypoints(routeWaypoints)
}

internal fun readEditorSnapshot(handle: SavedStateHandle): EditorSnapshot = editorSnapshot(
    modeName = handle.readForgiving<String>(EditorStateKeys.MODE),
    travelSpeedName = handle.readForgiving<String>(EditorStateKeys.TRAVEL_SPEED),
    endBehaviorName = handle.readForgiving<String>(EditorStateKeys.END_BEHAVIOR),
    selectedLatitude = handle.readForgiving<Double>(EditorStateKeys.SELECTED_LATITUDE),
    selectedLongitude = handle.readForgiving<Double>(EditorStateKeys.SELECTED_LONGITUDE),
    route = handle.readForgiving<DoubleArray>(EditorStateKeys.ROUTE),
)

/**
 * A saved value written by an older build (or touched by another owner of the same handle) can
 * have the wrong type under an editor key. `SavedStateHandle.get` performs an unchecked cast, so
 * a direct typed read would throw ClassCastException at the assignment. The `as?` check is inside
 * [runCatching], so a wrong type degrades to the editor default instead of crashing the restore.
 */
private inline fun <reified T> SavedStateHandle.readForgiving(key: String): T? =
    runCatching { get<Any?>(key) as? T }.getOrNull()

internal object EditorStateKeys {
    const val MODE = "editor.mode"
    const val SELECTED_LATITUDE = "editor.selectedLatitude"
    const val SELECTED_LONGITUDE = "editor.selectedLongitude"
    const val ROUTE = "editor.route"
    const val TRAVEL_SPEED = "editor.travelSpeed"
    const val END_BEHAVIOR = "editor.endBehavior"
}

/**
 * Decodes the saved values without touching SavedStateHandle, so a round trip is testable on the
 * JVM. Unknown enum names, malformed arrays and out-of-range coordinates fall back to the editor
 * defaults rather than failing the restore.
 */
internal fun editorSnapshot(
    modeName: String?,
    travelSpeedName: String?,
    endBehaviorName: String?,
    selectedLatitude: Double?,
    selectedLongitude: Double?,
    route: DoubleArray?,
): EditorSnapshot = EditorSnapshot(
    mode = enumValueOrDefault(modeName, SimulationMode.STATIC),
    selectedPoint = if (
        selectedLatitude != null &&
        selectedLongitude != null &&
        isValidCoordinate(selectedLatitude, selectedLongitude)
    ) {
        GeoPoint(selectedLatitude, selectedLongitude, CoordType.WGS84)
    } else {
        null
    },
    routeWaypoints = decodeRouteWaypoints(route),
    travelSpeed = enumValueOrDefault(travelSpeedName, TravelSpeeds.WALKING),
    endBehavior = enumValueOrDefault(endBehaviorName, EndBehavior.STOP),
)

/** Waypoints are WGS84: the UI converts at the map boundary, so anything held here is WGS84. */
internal fun encodeRouteWaypoints(waypoints: List<GeoPoint>): DoubleArray {
    val capped = waypoints.take(EditorSnapshot.MAX_ROUTE_WAYPOINTS)
    return DoubleArray(capped.size * 2) { index ->
        val point = capped[index / 2]
        if (index % 2 == 0) point.latitude else point.longitude
    }
}

internal fun decodeRouteWaypoints(flat: DoubleArray?): List<GeoPoint> {
    if (flat == null || flat.size < 2) return emptyList()
    val count = minOf(flat.size / 2, EditorSnapshot.MAX_ROUTE_WAYPOINTS)
    return buildList(count) {
        repeat(count) { index ->
            val latitude = flat[index * 2]
            val longitude = flat[index * 2 + 1]
            if (isValidCoordinate(latitude, longitude)) {
                add(GeoPoint(latitude, longitude, CoordType.WGS84))
            }
        }
    }
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
    name?.let { candidate -> enumValues<T>().firstOrNull { it.name == candidate } } ?: default

private fun isValidCoordinate(latitude: Double, longitude: Double): Boolean =
    latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
