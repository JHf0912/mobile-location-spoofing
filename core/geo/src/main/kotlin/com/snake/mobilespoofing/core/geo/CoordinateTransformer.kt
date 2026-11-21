package com.snake.mobilespoofing.core.geo

import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Converts between WGS84, GCJ02 and BD09.
 *
 * Both are needed in both directions: the map renders GCJ02 while the injectors require WGS84, so
 * every route crosses this boundary twice — once on the way in from a map tap, once on the way out
 * to be drawn.
 */
object CoordinateTransformer {

    private const val PI = 3.1415926535897932384626
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    /**
     * How many refinement passes the GCJ02 → WGS84 inverse runs. Three is comfortably enough to
     * reach sub-millimetre convergence; the loop is cheap and this runs once per waypoint.
     */
    private const val INVERSE_ITERATIONS = 3

    fun toWgs84(point: GeoPoint): GeoPoint = when (point.coordType) {
        CoordType.WGS84 -> point
        CoordType.GCJ02 -> gcjToWgs(point)
        CoordType.BD09 -> gcjToWgs(bdToGcj(point))
    }

    fun fromWgs84(point: GeoPoint, target: CoordType): GeoPoint = when (target) {
        CoordType.WGS84 -> point.copy(coordType = CoordType.WGS84)
        CoordType.GCJ02 -> wgsToGcj(point)
        CoordType.BD09 -> gcjToBd(wgsToGcj(point))
    }

    // ---------------------------------------------------------------- GCJ02

    fun wgsToGcj(point: GeoPoint): GeoPoint {
        if (skipOffset(point.latitude, point.longitude)) {
            return point.copy(coordType = CoordType.GCJ02)
        }
        val (lat, lng) = forwardOffset(point.latitude, point.longitude)
        return GeoPoint(lat, lng, CoordType.GCJ02)
    }

    /**
     * Inverts [wgsToGcj] by fixed-point refinement.
     *
     * The forward transform has no closed-form inverse, and the one-step approximation — "subtract
     * the offset computed at the GCJ02 point" — leaves tens of metres of error, which on a route is
     * the difference between the correct side of a street and the wrong one.
     */
    fun gcjToWgs(point: GeoPoint): GeoPoint {
        if (skipOffset(point.latitude, point.longitude)) {
            return point.copy(coordType = CoordType.WGS84)
        }
        var lat = point.latitude
        var lng = point.longitude
        repeat(INVERSE_ITERATIONS) {
            val (forwardLat, forwardLng) = forwardOffset(lat, lng)
            lat -= forwardLat - point.latitude
            lng -= forwardLng - point.longitude
        }
        return GeoPoint(lat, lng, CoordType.WGS84)
    }

    /** The forward GCJ02 disguise: where a true position appears to be on a GCJ02 map. */
    private fun forwardOffset(lat: Double, lng: Double): Pair<Double, Double> {
        val dLat = transformLat(lng - 105.0, lat - 35.0)
        val dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val mgLat = lat + (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        val mgLng = lng + (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return mgLat to mgLng
    }

    // ---------------------------------------------------------------- BD09

    fun bdToGcj(point: GeoPoint): GeoPoint {
        // Same guard as the GCJ02 path: outside China the datums coincide, and applying the
        // offset would move a point that is already correct.
        if (skipOffset(point.latitude, point.longitude)) {
            return point.copy(coordType = CoordType.GCJ02)
        }
        val x = point.longitude - 0.0065
        val y = point.latitude - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * PI)
        val theta = atan2(y, x) - 0.000003 * cos(x * PI)
        return GeoPoint(z * sin(theta), z * cos(theta), CoordType.GCJ02)
    }

    fun gcjToBd(point: GeoPoint): GeoPoint {
        if (skipOffset(point.latitude, point.longitude)) {
            return point.copy(coordType = CoordType.BD09)
        }
        val x = point.longitude
        val y = point.latitude
        val z = sqrt(x * x + y * y) + 0.00002 * sin(y * PI)
        val theta = atan2(y, x) + 0.000003 * cos(x * PI)
        return GeoPoint(z * sin(theta) + 0.006, z * cos(theta) + 0.0065, CoordType.BD09)
    }

    // ---------------------------------------------------------------- transform kernels

    private fun transformLat(lng: Double, lat: Double): Double {
        var ret = -100.0 + 2.0 * lng + 3.0 * lat + 0.2 * lat * lat + 0.1 * lng * lat +
            0.2 * sqrt(abs(lng))
        ret += (20.0 * sin(6.0 * lng * PI) + 20.0 * sin(2.0 * lng * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(lat * PI) + 40.0 * sin(lat / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(lat / 12.0 * PI) + 320 * sin(lat * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(lng: Double, lat: Double): Double {
        var ret = 300.0 + lng + 2.0 * lat + 0.1 * lng * lng + 0.1 * lng * lat +
            0.1 * sqrt(abs(lng))
        ret += (20.0 * sin(6.0 * lng * PI) + 20.0 * sin(2.0 * lng * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(lng * PI) + 40.0 * sin(lng / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(lng / 12.0 * PI) + 300.0 * sin(lng / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    /**
     * Whether the datum offset should be skipped. Two reasons: outside China the datums coincide,
     * and a non-finite coordinate is not a position — running the transform kernels on NaN/Infinity
     * would turn one bad value into two and corrupt the finite companion.
     */
    private fun skipOffset(lat: Double, lng: Double): Boolean =
        outOfChina(lat, lng) || !lat.isFinite() || !lng.isFinite()

    private fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
}
