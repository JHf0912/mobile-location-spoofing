package com.snake.mobilespoofing.core.geo

import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticMovementSourceTest {

    @Test
    fun `returns the configured point`() {
        val point = GeoPoint(39.9042, 116.4074, CoordType.WGS84)
        val source = StaticMovementSource(point)

        assertEquals(point, source.fixAt(0L).point)
        assertEquals(point, source.fixAt(60_000L).point)
    }

    @Test
    fun `rejects a point that is not WGS84`() {
        val exception = runCatching {
            StaticMovementSource(GeoPoint(39.9042, 116.4074, CoordType.GCJ02))
        }.exceptionOrNull()

        // Injecting GCJ02 as WGS84 is silently hundreds of metres off, so this is a hard error
        // rather than a conversion the source could get wrong.
        assertTrue(exception is IllegalArgumentException)
    }

    @Test
    fun `rejects a point with non-finite coordinates`() {
        listOf(
            GeoPoint(Double.NaN, 116.4074, CoordType.WGS84),
            GeoPoint(39.9042, Double.POSITIVE_INFINITY, CoordType.WGS84),
            GeoPoint(Double.NEGATIVE_INFINITY, 116.4074, CoordType.WGS84),
        ).forEach { point ->
            val exception = runCatching { StaticMovementSource(point) }.exceptionOrNull()
            assertTrue("$point must be rejected", exception is IllegalArgumentException)
        }
    }
}
