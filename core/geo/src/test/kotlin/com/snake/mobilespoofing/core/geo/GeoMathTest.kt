package com.snake.mobilespoofing.core.geo

import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cases here are the ones that produce silently wrong output rather than an exception — NaN
 * positions, routes that take the long way round the planet, headings that spin the wrong way.
 */
class GeoMathTest {

    // ---------------------------------------------------------------- distance

    @Test
    fun `distance between identical points is zero`() {
        val p = GeoPoint(39.9042, 116.4074, CoordType.WGS84)
        assertEquals(0.0, GeoMath.haversineDistanceMeters(p, p), 1e-6)
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        val d = GeoMath.haversineDistanceMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_194.9, d, 1.0)
    }

    @Test
    fun `distance across the antimeridian takes the short way round`() {
        // 0.2° of longitude at the equator is ~22 km. A naive implementation that normalises Δλ
        // first returns ~39 900 km instead.
        val d = GeoMath.haversineDistanceMeters(0.0, 179.9, 0.0, -179.9)
        assertEquals(22_239.0, d, 5.0)
    }

    @Test
    fun `antipodal points do not produce NaN`() {
        val d = GeoMath.haversineDistanceMeters(0.0, 0.0, 0.0, 180.0)
        assertFalse("distance must not be NaN", d.isNaN())
        assertEquals(20_015_114.0, d, 1000.0)
    }

    @Test
    fun `poles do not produce NaN`() {
        val d = GeoMath.haversineDistanceMeters(90.0, 0.0, -90.0, 0.0)
        assertFalse(d.isNaN())
        assertEquals(20_015_114.0, d, 1000.0)
    }

    // ---------------------------------------------------------------- bearing

    @Test
    fun `bearing due north is zero and due east is ninety`() {
        assertEquals(0.0, GeoMath.initialBearingDegrees(0.0, 0.0, 1.0, 0.0), 1e-6)
        assertEquals(90.0, GeoMath.initialBearingDegrees(0.0, 0.0, 0.0, 1.0), 1e-6)
    }

    @Test
    fun `bearing is always in range zero to three sixty`() {
        val b = GeoMath.initialBearingDegrees(0.0, 0.0, -1.0, -1.0)
        assertTrue("bearing was $b", b >= 0.0 && b < 360.0)
    }

    // ---------------------------------------------------------------- destination

    @Test
    fun `destination round trips with distance`() {
        val start = GeoPoint(39.9042, 116.4074, CoordType.WGS84)
        val moved = GeoMath.destination(start, bearingDeg = 45.0, distanceMeters = 1000.0)
        assertEquals(1000.0, GeoMath.haversineDistanceMeters(start, moved), 0.01)
    }

    @Test
    fun `destination round trips with bearing`() {
        val start = GeoPoint(39.9042, 116.4074, CoordType.WGS84)
        val moved = GeoMath.destination(start, bearingDeg = 137.0, distanceMeters = 2500.0)
        assertEquals(137.0, GeoMath.initialBearingDegrees(start, moved), 0.01)
    }

    @Test
    fun `destination normalises longitude across the antimeridian`() {
        val moved = GeoMath.destination(0.0, 179.0, bearingDeg = 90.0, distanceMeters = 200_000.0)
        assertTrue(
            "longitude should wrap into [-180, 180) but was ${moved.longitude}",
            moved.longitude >= -180.0 && moved.longitude < 180.0,
        )
    }

    // ---------------------------------------------------------------- interpolation

    @Test
    fun `interpolation endpoints return the original points`() {
        val a = GeoPoint(39.9042, 116.4074, CoordType.WGS84)
        val b = GeoPoint(31.2304, 121.4737, CoordType.WGS84)
        assertEquals(a.latitude, GeoMath.interpolate(a, b, 0.0).latitude, 1e-9)
        assertEquals(b.latitude, GeoMath.interpolate(a, b, 1.0).latitude, 1e-9)
    }

    @Test
    fun `interpolation midpoint is equidistant from both ends`() {
        val a = GeoPoint(39.9042, 116.4074, CoordType.WGS84)
        val b = GeoPoint(31.2304, 121.4737, CoordType.WGS84)
        val mid = GeoMath.interpolate(a, b, 0.5)
        assertEquals(
            GeoMath.haversineDistanceMeters(a, mid),
            GeoMath.haversineDistanceMeters(mid, b),
            1.0,
        )
    }

    @Test
    fun `interpolation between identical points returns that point`() {
        // sin(delta) is zero here, so a naive slerp yields 0/0 and NaN.
        val p = GeoPoint(39.9042, 116.4074, CoordType.WGS84)
        val mid = GeoMath.interpolate(p, p, 0.5)
        assertEquals(p.latitude, mid.latitude, 1e-12)
        assertFalse(mid.latitude.isNaN())
    }

    @Test
    fun `interpolation across the antimeridian stays on the short arc`() {
        val a = GeoPoint(0.0, 179.9, CoordType.WGS84)
        val b = GeoPoint(0.0, -179.9, CoordType.WGS84)
        val mid = GeoMath.interpolate(a, b, 0.5)
        // The midpoint must sit near ±180, not near the prime meridian.
        assertTrue("midpoint longitude was ${mid.longitude}", kotlin.math.abs(mid.longitude) > 179.0)
    }

    // ---------------------------------------------------------------- normalisation

    @Test
    fun `bearing normalisation wraps negatives forward`() {
        assertEquals(350.0, GeoMath.normalizeBearing(-10.0), 1e-9)
        assertEquals(0.0, GeoMath.normalizeBearing(360.0), 1e-9)
    }

    @Test
    fun `longitude normalisation wraps into the valid range`() {
        assertEquals(-170.0, GeoMath.normalizeLongitude(190.0), 1e-9)
        assertEquals(170.0, GeoMath.normalizeLongitude(-190.0), 1e-9)
    }

    @Test
    fun `shortest angle delta crosses the zero boundary the short way`() {
        // The naive difference would be -350, i.e. almost a full turn the wrong way.
        assertEquals(20.0, GeoMath.shortestAngleDelta(350.0, 10.0), 1e-9)
        assertEquals(-20.0, GeoMath.shortestAngleDelta(10.0, 350.0), 1e-9)
    }

    @Test
    fun `shortest angle delta is never more than half a turn`() {
        for (from in 0 until 360 step 17) {
            for (to in 0 until 360 step 23) {
                val d = GeoMath.shortestAngleDelta(from.toDouble(), to.toDouble())
                assertTrue("delta $d from $from to $to", d > -180.0001 && d <= 180.0001)
            }
        }
    }

    // ---------------------------------------------------------------- offsets

    @Test
    fun `offset north by a known distance lands the expected distance away`() {
        val p = GeoPoint(39.9042, 116.4074, CoordType.WGS84)
        val moved = GeoMath.offsetMeters(p, eastMeters = 0.0, northMeters = 500.0)
        assertEquals(500.0, GeoMath.haversineDistanceMeters(p, moved), 1.0)
    }

    @Test
    fun `a fixed distance spans more longitude degrees further from the equator`() {
        // A degree of longitude covers less ground as latitude rises, so the same metre offset
        // maps to more degrees. Getting this backwards puts every east-west jitter and offset in
        // the wrong place.
        val atEquator = GeoMath.metersToLngDegrees(1000.0, 0.0)
        val atBeijing = GeoMath.metersToLngDegrees(1000.0, 39.9)
        assertTrue("expected $atBeijing > $atEquator", atBeijing > atEquator)
    }
}
