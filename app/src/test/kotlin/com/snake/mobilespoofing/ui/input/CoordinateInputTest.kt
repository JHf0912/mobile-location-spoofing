package com.snake.mobilespoofing.ui.input

import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the accepted coordinate syntax and, just as important, the datum of each form.
 *
 * Typed `lat,lon` uses the caller's default datum; every `geo:` form is WGS84 by definition. The
 * map tap path is GCJ02 and is intentionally not used here.
 */
class CoordinateInputTest {

    private fun ok(raw: String, defaultDatum: CoordType = CoordType.WGS84): GeoPoint {
        val result = parseCoordinateInput(raw, defaultDatum)
        assertTrue("expected Ok for '$raw' but was $result", result is CoordinateParseResult.Ok)
        return (result as CoordinateParseResult.Ok).point
    }

    private fun invalid(raw: String, expected: CoordinateParseError) {
        assertEquals(
            "wrong rejection for '$raw'",
            CoordinateParseResult.Invalid(expected),
            parseCoordinateInput(raw),
        )
    }

    @Test
    fun `plain pair is wgs84 by default`() {
        assertEquals(
            GeoPoint(39.9042, 116.4074, CoordType.WGS84),
            ok("39.9042,116.4074"),
        )
    }

    @Test
    fun `plain pair is whitespace tolerant`() {
        assertEquals(
            GeoPoint(39.9042, 116.4074, CoordType.WGS84),
            ok("  39.9042 ,  116.4074  "),
        )
    }

    @Test
    fun `plain pair honours the caller's default datum`() {
        val point = ok("39.9042,116.4074", CoordType.GCJ02)

        assertEquals(GeoPoint(39.9042, 116.4074, CoordType.GCJ02), point)
    }

    @Test
    fun `negative coordinates are accepted`() {
        assertEquals(
            GeoPoint(-33.8688, -70.6693, CoordType.WGS84),
            ok("-33.8688,-70.6693"),
        )
    }

    @Test
    fun `bounds are inclusive`() {
        assertEquals(GeoPoint(90.0, 180.0, CoordType.WGS84), ok("90,180"))
        assertEquals(GeoPoint(-90.0, -180.0, CoordType.WGS84), ok("-90,-180"))
    }

    @Test
    fun `geo pair is always wgs84 even when the default is not`() {
        assertEquals(
            GeoPoint(39.9042, 116.4074, CoordType.WGS84),
            ok("geo:39.9042,116.4074", CoordType.GCJ02),
        )
    }

    @Test
    fun `geo uri with altitude is accepted and uses the coordinate pair`() {
        assertEquals(
            GeoPoint(39.9042, 116.4074, CoordType.WGS84),
            ok("geo:39.9042,116.4074?z=50"),
        )
    }

    @Test
    fun `geo uri with a q pair and a label uses the q pair`() {
        assertEquals(
            GeoPoint(31.2304, 121.4737, CoordType.WGS84),
            ok("geo:0,0?q=31.2304,121.4737(Office)"),
        )
    }

    @Test
    fun `geo uri with a q pair without a label is accepted`() {
        assertEquals(
            GeoPoint(31.2304, 121.4737, CoordType.WGS84),
            ok("geo:0,0?q=31.2304,121.4737"),
        )
    }

    @Test
    fun `a coordinate q pair wins over the base pair`() {
        assertEquals(
            GeoPoint(31.2, 121.4, CoordType.WGS84),
            ok("geo:31.2,121.4?q=31.2,121.4"),
        )
        assertEquals(
            GeoPoint(31.2, 121.4, CoordType.WGS84),
            ok("geo:1,2?q=31.2,121.4"),
        )
    }

    @Test
    fun `a label q falls back to a usable base pair`() {
        assertEquals(
            GeoPoint(1.0, 2.0, CoordType.WGS84),
            ok("geo:1,2?q=Beijing"),
        )
        assertEquals(
            GeoPoint(1.0, 2.0, CoordType.WGS84),
            ok("geo:1,2?q="),
        )
    }

    @Test
    fun `a label q with the 0,0 placeholder base is rejected`() {
        invalid("geo:0,0?q=Office", CoordinateParseError.Malformed)
        invalid("geo:0,0?q=", CoordinateParseError.Malformed)
    }

    @Test
    fun `an out-of-range coordinate q is reported instead of falling back to the base`() {
        invalid("geo:1,2?q=91,0", CoordinateParseError.OutOfRange)
        invalid("geo:1,2?q=NaN,0", CoordinateParseError.NotFinite)
    }

    @Test
    fun `a label q with an out-of-range base is rejected with the base reason`() {
        invalid("geo:999,0?q=Office", CoordinateParseError.OutOfRange)
    }

    @Test
    fun `a percent-encoded q pair is decoded`() {
        assertEquals(
            GeoPoint(31.2, 121.4, CoordType.WGS84),
            ok("geo:0,0?q=31.2%2C121.4"),
        )
        assertEquals(
            GeoPoint(31.2, 121.4, CoordType.WGS84),
            ok("geo:0,0?q=31.2%2c121.4"),
        )
    }

    @Test
    fun `geo scheme is matched case-insensitively`() {
        assertEquals(
            GeoPoint(1.0, 2.0, CoordType.WGS84),
            ok("GEO:1,2"),
        )
    }

    @Test
    fun `out of range latitude is rejected`() {
        invalid("91,0", CoordinateParseError.OutOfRange)
        invalid("-90.0001,0", CoordinateParseError.OutOfRange)
    }

    @Test
    fun `out of range longitude is rejected`() {
        invalid("0,181", CoordinateParseError.OutOfRange)
        invalid("0,-180.0001", CoordinateParseError.OutOfRange)
    }

    @Test
    fun `nan is rejected as not finite`() {
        invalid("NaN,0", CoordinateParseError.NotFinite)
        invalid("0,NaN", CoordinateParseError.NotFinite)
    }

    @Test
    fun `infinity is rejected as not finite`() {
        invalid("Infinity,0", CoordinateParseError.NotFinite)
        invalid("-Infinity,0", CoordinateParseError.NotFinite)
        invalid("1e999,0", CoordinateParseError.NotFinite)
    }

    @Test
    fun `empty input is rejected as empty`() {
        invalid("", CoordinateParseError.Empty)
        invalid("   ", CoordinateParseError.Empty)
    }

    @Test
    fun `malformed input is rejected`() {
        invalid("abc", CoordinateParseError.Malformed)
        invalid("1", CoordinateParseError.Malformed)
        invalid("1,", CoordinateParseError.Malformed)
        invalid(",2", CoordinateParseError.Malformed)
        invalid("39.9 116.4", CoordinateParseError.Malformed)
        invalid("geo:", CoordinateParseError.Malformed)
        invalid("geo:0,0?q=91,0,5", CoordinateParseError.Malformed)
    }

    @Test
    fun `extra tokens are rejected`() {
        invalid("1,2,3", CoordinateParseError.Malformed)
        invalid("1,2,", CoordinateParseError.Malformed)
        invalid("geo:1,2,3", CoordinateParseError.Malformed)
        invalid("39.9,116.4?z=5", CoordinateParseError.Malformed)
    }

    @Test
    fun `a plain pair is not a geo uri and keeps its datum`() {
        val result = parseCoordinateInput("1,2", CoordType.GCJ02)
        assertEquals(
            CoordinateParseResult.Ok(GeoPoint(1.0, 2.0, CoordType.GCJ02)),
            result,
        )
    }
}
