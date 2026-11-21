package com.snake.mobilespoofing.core.geo

import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {

    private val origin = GeoPoint(39.9042, 116.4074, CoordType.WGS84)

    /** Builds an eastward route whose legs are exactly the given lengths. */
    private fun routeOf(vararg legMeters: Double, closedLoop: Boolean = false): Route {
        var point = origin
        val points = mutableListOf(point)
        legMeters.forEach { leg ->
            point = GeoMath.destination(point, bearingDeg = 90.0, distanceMeters = leg)
            points += point
        }
        return Route.of(points, closedLoop)
    }

    // ---------------------------------------------------------------- construction

    @Test(expected = IllegalArgumentException::class)
    fun `a route needs at least two waypoints`() {
        Route.of(listOf(origin))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a route rejects non-WGS84 waypoints`() {
        // Handing it GCJ02 straight from the map UI is the mistake this guards against: nothing
        // downstream would notice a several-hundred-metre shift.
        Route.of(
            listOf(
                GeoPoint(39.9042, 116.4074, CoordType.GCJ02),
                GeoPoint(39.9042, 116.4174, CoordType.GCJ02),
            ),
        )
    }

    @Test
    fun `a route rejects non-finite waypoints instead of silently dropping them`() {
        // Without the finite guard the NaN haversine distance makes the dedup treat the point as a
        // duplicate and drop it silently; it must fail loudly instead.
        listOf(
            GeoPoint(Double.NaN, 116.4074, CoordType.WGS84),
            GeoPoint(39.9042, Double.POSITIVE_INFINITY, CoordType.WGS84),
            GeoPoint(Double.NEGATIVE_INFINITY, 116.4074, CoordType.WGS84),
        ).forEach { bad ->
            val exception = runCatching { Route.of(listOf(origin, bad)) }.exceptionOrNull()
            assertTrue("$bad must be rejected", exception is IllegalArgumentException)
        }
    }

    @Test
    fun `consecutive duplicate waypoints are dropped`() {
        val a = origin
        val b = GeoMath.destination(a, 90.0, 1000.0)
        val route = Route.of(listOf(a, a, b))
        assertEquals(2, route.waypointsWgs84.size)
        assertEquals(1000.0, route.totalMeters, 0.5)
    }

    @Test
    fun `a closed loop includes the closing leg in its length`() {
        val open = routeOf(1000.0, 1000.0)
        val closed = routeOf(1000.0, 1000.0, closedLoop = true)
        assertEquals(2000.0, open.totalMeters, 1.0)
        // Out 1000, out another 1000, then ~2000 back to the start.
        assertEquals(4000.0, closed.totalMeters, 2.0)
    }

    // ---------------------------------------------------------------- positionAt

    @Test
    fun `positionAt clamps instead of throwing`() {
        val route = routeOf(1000.0)
        // Total function by design: end-of-route behaviour lives in the movement source, so this
        // must never throw or return NaN whatever it is handed.
        assertEquals(0.0, route.positionAt(-500.0).distanceIntoTrip, 1e-9)
        // Compared against the computed length, not a literal: haversine yields 1000.0000000046
        // for a nominal 1000 m leg.
        assertEquals(route.totalMeters, route.positionAt(99_999.0).distanceIntoTrip, 1e-9)
        assertEquals(0.0, route.positionAt(Double.NaN).distanceIntoTrip, 1e-9)
        assertEquals(0.0, route.positionAt(Double.POSITIVE_INFINITY).distanceIntoTrip, 1e-9)
    }

    @Test
    fun `positionAt halfway is halfway along`() {
        val route = routeOf(1000.0)
        val mid = route.positionAt(500.0)
        assertEquals(500.0, GeoMath.haversineDistanceMeters(origin, mid.point), 1.0)
        assertEquals(0.5, mid.fractionOfTrip, 1e-6)
    }

    @Test
    fun `positionAt reports the bearing of the leg travelled`() {
        val route = routeOf(1000.0)
        assertEquals(90.0, route.positionAt(500.0).bearingDeg, 0.5)
    }

    @Test
    fun `positionAt on the closing leg of a loop wraps back to the first waypoint`() {
        val route = routeOf(1000.0, closedLoop = true)
        val onClosingLeg = route.positionAt(1500.0)   // 500 m into the 1000 m return leg
        assertEquals(1, onClosingLeg.legIndex)
        // Halfway back means about 1500 m from the origin... in the reverse direction.
        assertEquals(500.0, GeoMath.haversineDistanceMeters(origin, onClosingLeg.point), 50.0)
    }

    @Test
    fun `positionAt on the final leg of an open route lands on the last waypoint`() {
        val route = routeOf(1000.0)
        val end = route.positionAt(1000.0)
        val last = route.waypointsWgs84.last()
        assertEquals(0.0, GeoMath.haversineDistanceMeters(last, end.point), 0.5)
    }

    @Test
    fun `positionAt negative infinity clamps to the start`() {
        val route = routeOf(1000.0)
        assertEquals(0.0, route.positionAt(Double.NEGATIVE_INFINITY).distanceIntoTrip, 1e-9)
    }
}
