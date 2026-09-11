package com.snake.mobilespoofing.core.geo

import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateTransformerTest {

    private val beijingGcj = GeoPoint(39.908823, 116.39747, CoordType.GCJ02)
    private val beijingWgs = GeoPoint(39.90742, 116.39123, CoordType.WGS84)
    private val shanghaiWgs = GeoPoint(31.2304, 121.4737, CoordType.WGS84)

    private fun distance(a: GeoPoint, b: GeoPoint) = GeoMath.haversineDistanceMeters(a, b)

    @Test
    fun `gcj02 converts to a known wgs84 point within a few metres`() {
        // Was previously asserted with a 150 m tolerance, which was not a specification — it was
        // the error of the one-step approximate inverse. On a route, 150 m is the wrong street.
        val wgs = CoordinateTransformer.toWgs84(beijingGcj)
        assertEquals(CoordType.WGS84, wgs.coordType)
        assertTrue("error was ${distance(wgs, beijingWgs)} m", distance(wgs, beijingWgs) < 5.0)
    }

    @Test
    fun `wgs84 survives a round trip through gcj02`() {
        val roundTripped = CoordinateTransformer.gcjToWgs(CoordinateTransformer.wgsToGcj(shanghaiWgs))
        assertTrue(
            "round-trip error was ${distance(roundTripped, shanghaiWgs)} m",
            distance(roundTripped, shanghaiWgs) < 0.5,
        )
    }

    @Test
    fun `gcj02 survives a round trip through wgs84`() {
        val roundTripped = CoordinateTransformer.wgsToGcj(CoordinateTransformer.gcjToWgs(beijingGcj))
        assertTrue(
            "round-trip error was ${distance(roundTripped, beijingGcj)} m",
            distance(roundTripped, beijingGcj) < 0.5,
        )
    }

    @Test
    fun `bd09 survives a round trip`() {
        val original = GeoPoint(39.915, 116.404, CoordType.BD09)
        val roundTripped = CoordinateTransformer.gcjToBd(CoordinateTransformer.bdToGcj(original))
        assertTrue(
            "round-trip error was ${distance(roundTripped, original)} m",
            distance(roundTripped, original) < 1.0,
        )
    }

    @Test
    fun `wgs84 input passes through unchanged`() {
        val result = CoordinateTransformer.toWgs84(shanghaiWgs)
        assertEquals(shanghaiWgs.latitude, result.latitude, 0.0)
        assertEquals(shanghaiWgs.longitude, result.longitude, 0.0)
    }

    @Test
    fun `fromWgs84 produces the requested datum`() {
        // The map renders GCJ02 while the injectors need WGS84, so this direction is travelled
        // every time a route is drawn.
        assertEquals(CoordType.WGS84, CoordinateTransformer.fromWgs84(shanghaiWgs, CoordType.WGS84).coordType)
        assertEquals(CoordType.GCJ02, CoordinateTransformer.fromWgs84(shanghaiWgs, CoordType.GCJ02).coordType)
        assertEquals(CoordType.BD09, CoordinateTransformer.fromWgs84(shanghaiWgs, CoordType.BD09).coordType)
    }

    @Test
    fun `fromWgs84 then toWgs84 returns the original point`() {
        for (target in CoordType.entries) {
            val there = CoordinateTransformer.fromWgs84(shanghaiWgs, target)
            val back = CoordinateTransformer.toWgs84(there)
            assertTrue(
                "round trip via $target was ${distance(back, shanghaiWgs)} m",
                distance(back, shanghaiWgs) < 1.0,
            )
        }
    }

    @Test
    fun `points outside china are left alone`() {
        // The datum offsets only exist inside China; applying them elsewhere would move a point
        // that is already correct.
        val london = GeoPoint(51.5074, -0.1278, CoordType.WGS84)
        val asGcj = CoordinateTransformer.wgsToGcj(london)
        assertEquals(london.latitude, asGcj.latitude, 1e-9)
        assertEquals(london.longitude, asGcj.longitude, 1e-9)
    }
}
