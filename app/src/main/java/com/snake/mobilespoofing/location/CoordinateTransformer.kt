package com.snake.mobilespoofing.location

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object CoordinateTransformer {
    private const val PI = 3.1415926535897932384626
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun toWgs84(point: GeoPoint): GeoPoint {
        return when (point.coordType) {
            CoordType.WGS84 -> point
            CoordType.GCJ02 -> gcjToWgs(point)
            CoordType.BD09 -> gcjToWgs(bdToGcj(point))
        }
    }

    private fun bdToGcj(point: GeoPoint): GeoPoint {
        val x = point.longitude - 0.0065
        val y = point.latitude - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * PI)
        val theta = atan2(y, x) - 0.000003 * cos(x * PI)
        val ggLng = z * cos(theta)
        val ggLat = z * sin(theta)
        return GeoPoint(ggLat, ggLng, CoordType.GCJ02)
    }

    private fun gcjToWgs(point: GeoPoint): GeoPoint {
        if (outOfChina(point.latitude, point.longitude)) {
            return GeoPoint(point.latitude, point.longitude, CoordType.WGS84)
        }
        val dLat = transformLat(point.longitude - 105.0, point.latitude - 35.0)
        val dLng = transformLng(point.longitude - 105.0, point.latitude - 35.0)
        val radLat = point.latitude / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val mgLat = point.latitude + (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        val mgLng = point.longitude + (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return GeoPoint(point.latitude * 2 - mgLat, point.longitude * 2 - mgLng, CoordType.WGS84)
    }

    private fun transformLat(lng: Double, lat: Double): Double {
        var ret = -100.0 + 2.0 * lng + 3.0 * lat + 0.2 * lat * lat + 0.1 * lng * lat + 0.2 * sqrt(abs(lng))
        ret += (20.0 * sin(6.0 * lng * PI) + 20.0 * sin(2.0 * lng * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(lat * PI) + 40.0 * sin(lat / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(lat / 12.0 * PI) + 320 * sin(lat * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(lng: Double, lat: Double): Double {
        var ret = 300.0 + lng + 2.0 * lat + 0.1 * lng * lng + 0.1 * lng * lat + 0.1 * sqrt(abs(lng))
        ret += (20.0 * sin(6.0 * lng * PI) + 20.0 * sin(2.0 * lng * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(lng * PI) + 40.0 * sin(lng / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(lng / 12.0 * PI) + 300.0 * sin(lng / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    private fun outOfChina(lat: Double, lng: Double): Boolean {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
    }
}
