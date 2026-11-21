package com.snake.mobilespoofing.core.geo

import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These are pure arithmetic — no coroutines, no dispatchers, no wall clock — which is the payoff of
 * `fixAt(elapsedMs)` as the primitive rather than a Flow.
 */
class RouteMovementSourceTest {

    private val origin = GeoPoint(39.9042, 116.4074, CoordType.WGS84)

    /** An eastward route whose legs are exactly the given lengths. */
    private fun routeOf(vararg legMeters: Double, closedLoop: Boolean = false): Route {
        var point = origin
        val points = mutableListOf(point)
        legMeters.forEach { leg ->
            point = GeoMath.destination(point, bearingDeg = 90.0, distanceMeters = leg)
            points += point
        }
        return Route.of(points, closedLoop)
    }

    // ---------------------------------------------------------------- the core invariant

    @Test
    fun `distance travelled equals speed times elapsed time`() {
        val route = routeOf(1000.0)
        val source = RouteMovementSource(route, speedMetersPerSecond = 10.0, endBehavior = EndBehavior.STOP)

        assertEquals(300.0, GeoMath.haversineDistanceMeters(origin, source.fixAt(30_000).point), 0.5)
    }

    @Test
    fun `a tick that fires late reports the correct position rather than lagging`() {
        // The whole reason position is a function of absolute time: a delayed tick must not lose
        // ground permanently.
        val route = routeOf(1000.0)
        val source = RouteMovementSource(route, speedMetersPerSecond = 10.0, endBehavior = EndBehavior.STOP)

        assertEquals(100.0, GeoMath.haversineDistanceMeters(origin, source.fixAt(10_000).point), 0.5)
        assertEquals(600.0, GeoMath.haversineDistanceMeters(origin, source.fixAt(60_000).point), 0.5)
    }

    // ---------------------------------------------------------------- end behaviours

    @Test
    fun `stop ends at the final waypoint and reports completion`() {
        val route = routeOf(1000.0)
        val source = RouteMovementSource(route, speedMetersPerSecond = 10.0, endBehavior = EndBehavior.STOP)

        assertEquals(100_000L, source.durationMs)
        assertFalse(source.isComplete(99_999L))
        assertTrue(source.isComplete(100_000L))

        val past = source.fixAt(500_000L)
        assertEquals(0f, past.speedMetersPerSecond, 1e-6f)
        assertFalse("a stopped receiver reports no heading", past.hasBearing)
    }

    @Test
    fun `loop returns to the start after one full cycle`() {
        val route = routeOf(1000.0, closedLoop = true)
        val source = RouteMovementSource(route, speedMetersPerSecond = 10.0, endBehavior = EndBehavior.LOOP)

        val cycleMs = ((route.totalMeters / 10.0) * 1000.0).toLong()
        val atStart = source.fixAt(0L).point
        val afterCycle = source.fixAt(cycleMs).point

        assertEquals(0.0, GeoMath.haversineDistanceMeters(atStart, afterCycle), 2.0)
        assertTrue("a loop never completes on its own", source.durationMs == null)
    }

    @Test
    fun `ping pong reverses the heading on the return leg`() {
        val route = routeOf(1000.0)
        val source = RouteMovementSource(route, speedMetersPerSecond = 10.0, endBehavior = EndBehavior.PING_PONG)

        // 500 m out, then 500 m into the return leg (cycle is 2000 m).
        assertEquals(90.0f, source.fixAt(50_000L).bearingDegrees, 0.5f)
        assertEquals(270.0f, source.fixAt(150_000L).bearingDegrees, 0.5f)
    }

    @Test
    fun `ping pong returns to the start at the halfway point of a cycle`() {
        val route = routeOf(1000.0)
        val source = RouteMovementSource(route, speedMetersPerSecond = 10.0, endBehavior = EndBehavior.PING_PONG)

        // 100 s at 10 m/s is 1000 m, i.e. the far end.
        assertEquals(1000.0, GeoMath.haversineDistanceMeters(origin, source.fixAt(100_000L).point), 1.0)
    }

    // ---------------------------------------------------------------- guards

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive speed is rejected`() {
        // Otherwise travelled/speed is Infinity and Infinity.mod(x) is NaN.
        RouteMovementSource(routeOf(1000.0), speedMetersPerSecond = 0.0, endBehavior = EndBehavior.STOP)
    }

    @Test
    fun `a moving fix reports a heading and a speed`() {
        val source = RouteMovementSource(routeOf(1000.0), 10.0, EndBehavior.STOP)
        val fix = source.fixAt(10_000L)
        assertTrue(fix.hasBearing)
        assertEquals(10.0f, fix.speedMetersPerSecond, 1e-4f)
        assertEquals(90.0f, fix.bearingDegrees, 0.5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `loop on an open route is rejected instead of teleporting back to the start`() {
        // LOOP wraps the travelled distance modulo the route length; an open route has no closing
        // leg, so the wrap would jump from the last waypoint straight back to the first.
        RouteMovementSource(routeOf(1000.0), speedMetersPerSecond = 10.0, endBehavior = EndBehavior.LOOP)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a loop route spec with closedLoop false is rejected`() {
        val a = origin
        val b = GeoMath.destination(a, bearingDeg = 90.0, distanceMeters = 1000.0)
        MovementSpec.RouteSpec(
            waypointsWgs84 = listOf(a, b),
            speedMetersPerSecond = 10.0,
            endBehavior = EndBehavior.LOOP,
            closedLoop = false,
        ).toMovementSource()
    }
}
