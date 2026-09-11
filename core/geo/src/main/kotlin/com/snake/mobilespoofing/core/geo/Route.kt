package com.snake.mobilespoofing.core.geo

import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint

/** A position along a [Route], with the direction of travel at that point. */
data class RoutePosition(
    val point: GeoPoint,
    val bearingDeg: Double,
    val legIndex: Int,
    val distanceIntoTrip: Double,
    val fractionOfTrip: Double,
)

/**
 * An immutable polyline with precomputed leg lengths.
 *
 * Not a `data class`: it holds a `DoubleArray`, whose generated `equals` would compare by reference
 * and quietly misbehave anywhere the route is used as a value.
 */
class Route private constructor(
    val waypointsWgs84: List<GeoPoint>,
    private val cumulativeMeters: DoubleArray,
    val totalMeters: Double,
    val closedLoop: Boolean,
) {

    private val legCount: Int get() = cumulativeMeters.size - 1

    /**
     * Position at [distanceMeters] along the route.
     *
     * A total function: it clamps instead of throwing or returning NaN, for any input including
     * negative distances, values past the end, and NaN/Infinity. End-of-route behaviour belongs to
     * the movement source, so that this stays branch-free and cannot fail.
     */
    fun positionAt(distanceMeters: Double): RoutePosition {
        // coerceIn alone is not enough here: every comparison against NaN is false, so NaN slips
        // through the clamp untouched and poisons the interpolation downstream.
        val d = if (distanceMeters.isFinite()) distanceMeters.coerceIn(0.0, totalMeters) else 0.0
        val index = legIndexFor(d)

        val from = waypointsWgs84[index]
        // The modulo is what makes the closing leg of a loop work without a special case: for a
        // closed route the last waypoint's successor is the first, and for an open route the last
        // leg index is size - 2, whose successor is size - 1.
        val to = waypointsWgs84[(index + 1) % waypointsWgs84.size]

        val legStart = cumulativeMeters[index]
        val legLength = (cumulativeMeters[index + 1] - legStart).coerceAtLeast(1e-9)
        val fraction = ((d - legStart) / legLength).coerceIn(0.0, 1.0)

        return RoutePosition(
            point = GeoMath.interpolate(from, to, fraction),
            bearingDeg = GeoMath.initialBearingDegrees(from, to),
            legIndex = index,
            distanceIntoTrip = d,
            fractionOfTrip = if (totalMeters > 0) d / totalMeters else 0.0,
        )
    }

    private fun legIndexFor(distanceMeters: Double): Int {
        val found = cumulativeMeters.binarySearch(distanceMeters)
        // An exact hit on the final cumulative value would index a leg that does not exist.
        val raw = if (found >= 0) found else -found - 2
        return raw.coerceIn(0, legCount - 1)
    }

    companion object {
        /**
         * Consecutive waypoints closer than this are dropped. A zero-length leg stalls the route
         * for a tick and returns a meaningless bearing, which is what a double-tap on the map
         * produces.
         */
        private const val MIN_LEG_METERS = 0.05

        /**
         * @param closedLoop when true the ring is closed by returning to the first waypoint, so the
         *   total length includes the closing leg. [EndBehavior.LOOP] then walks the ring rather
         *   than teleporting back — an instantaneous jump of several hundred metres is exactly
         *   what anything sampling position at 1 Hz would notice.
         */
        fun of(pointsWgs84: List<GeoPoint>, closedLoop: Boolean = false): Route {
            require(pointsWgs84.size >= 2) {
                "A route needs at least 2 waypoints, got ${pointsWgs84.size}"
            }
            // Failing loudly here is the point. Imported GPX/KML is WGS84 while the map UI hands
            // back GCJ02; mixing them shifts every position by hundreds of metres, and nothing
            // downstream would ever notice.
            require(pointsWgs84.all { it.coordType == CoordType.WGS84 }) {
                "Route waypoints must be WGS84 — convert with CoordinateTransformer first"
            }

            val points = buildList {
                pointsWgs84.forEach { candidate ->
                    val previous = lastOrNull()
                    if (previous == null ||
                        GeoMath.haversineDistanceMeters(previous, candidate) >= MIN_LEG_METERS
                    ) {
                        add(candidate)
                    }
                }
                if (closedLoop && size >= 2 &&
                    GeoMath.haversineDistanceMeters(first(), last()) < MIN_LEG_METERS
                ) {
                    removeAt(lastIndex)
                }
            }
            require(points.size >= 2) {
                "Route degenerates to a single point after removing duplicates"
            }

            val legs = if (closedLoop) points.size else points.size - 1
            val cumulative = DoubleArray(legs + 1)
            for (i in 0 until legs) {
                val from = points[i]
                val to = points[(i + 1) % points.size]
                cumulative[i + 1] = cumulative[i] + GeoMath.haversineDistanceMeters(from, to)
            }

            return Route(points, cumulative, cumulative[legs], closedLoop)
        }
    }
}
