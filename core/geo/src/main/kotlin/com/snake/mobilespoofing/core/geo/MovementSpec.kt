package com.snake.mobilespoofing.core.geo

import com.snake.mobilespoofing.core.model.GeoPoint

/**
 * What a session should simulate.
 *
 * Waypoints are WGS84. The map UI produces GCJ02, so conversion happens at the UI boundary — see
 * [toMovementSource], which is the single place that turns a spec into something runnable.
 */
sealed interface MovementSpec {

    /** Teleport to a point and hold there. */
    data class Static(val pointWgs84: GeoPoint) : MovementSpec

    data class RouteSpec(
        val waypointsWgs84: List<GeoPoint>,
        val speedMetersPerSecond: Double,
        val endBehavior: EndBehavior,
        /**
         * Whether the ring is closed by returning to the first waypoint. Meaningful for
         * [EndBehavior.LOOP]; ignored otherwise. Defaults to false so a caller that forgets it
         * cannot make a STOP route end at the first waypoint instead of the last.
         */
        val closedLoop: Boolean = false,
    ) : MovementSpec
}

/**
 * Builds the runnable source for this spec.
 *
 * Throws [IllegalArgumentException] when the spec is not runnable — too few waypoints, a mixed
 * datum, a non-positive speed. Callers should surface the message rather than falling back to
 * something plausible, because every one of those cases means the user would otherwise be shown a
 * position that is not what they asked for.
 */
fun MovementSpec.toMovementSource(): MovementSource = when (this) {
    is MovementSpec.Static -> StaticMovementSource(pointWgs84)

    is MovementSpec.RouteSpec -> RouteMovementSource(
        route = Route.of(waypointsWgs84, closedLoop),
        speedMetersPerSecond = speedMetersPerSecond,
        endBehavior = endBehavior,
    )
}
