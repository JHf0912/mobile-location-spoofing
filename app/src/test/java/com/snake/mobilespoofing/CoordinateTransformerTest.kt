package com.snake.mobilespoofing

import com.snake.mobilespoofing.location.CoordType
import com.snake.mobilespoofing.location.CoordinateTransformer
import com.snake.mobilespoofing.location.GeoPoint
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class CoordinateTransformerTest {

    @Test
    fun gcj_to_wgs_has_small_error_for_known_point() {
        val gcj = GeoPoint(latitude = 39.908823, longitude = 116.39747, coordType = CoordType.GCJ02)
        val wgs = CoordinateTransformer.toWgs84(gcj)

        val expectedWgs = GeoPoint(latitude = 39.90742, longitude = 116.39123, coordType = CoordType.WGS84)
        val distance = haversineMeters(wgs.latitude, wgs.longitude, expectedWgs.latitude, expectedWgs.longitude)

        assertTrue("distance=$distance", distance < 150.0)
    }

    @Test
    fun wgs_input_returns_same() {
        val point = GeoPoint(31.2304, 121.4737, CoordType.WGS84)
        val result = CoordinateTransformer.toWgs84(point)
        assertTrue(point.latitude == result.latitude && point.longitude == result.longitude)
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return radius * c
    }
}
