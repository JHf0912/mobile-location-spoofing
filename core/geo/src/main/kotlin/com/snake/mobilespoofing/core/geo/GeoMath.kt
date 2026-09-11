package com.snake.mobilespoofing.core.geo

import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spherical geometry on the WGS84 mean-radius sphere.
 *
 * Spherical rather than ellipsoidal (Vincenty) on purpose: `android.location.Location.distanceBetween`
 * is Vincenty-accurate but is a stub under plain JVM unit tests, which would push all of this out of
 * reach of the fast test path. The spherical error is under 0.5% over a few kilometres — far below
 * anything that matters for a mock position.
 *
 * Every function here takes and returns WGS84. Coordinates in other datums must be converted first;
 * mixing them silently shifts positions by hundreds of metres.
 */
object GeoMath {

    /** IUGG mean Earth radius, in metres. */
    const val EARTH_RADIUS_M: Double = 6_371_008.8

    /** Metres per degree of latitude. Constant enough; longitude scales by cos(latitude). */
    const val METERS_PER_DEGREE_LAT: Double = 111_320.0

    // ---------------------------------------------------------------- distance & bearing

    fun haversineDistanceMeters(a: GeoPoint, b: GeoPoint): Double =
        haversineDistanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

    fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        // Deliberately NOT normalising Δλ: sin is periodic, so the raw difference is already
        // antimeridian-safe. Normalising first is precisely where antimeridian bugs come from.
        val dLambda = Math.toRadians(lon2 - lon1)

        val sinHalfPhi = sin(dPhi / 2)
        val sinHalfLambda = sin(dLambda / 2)
        val a = sinHalfPhi * sinHalfPhi + cos(phi1) * cos(phi2) * sinHalfLambda * sinHalfLambda

        // Rounding can push `a` a hair above 1.0, and sqrt(1 - a) then yields NaN — which would
        // poison every subsequent position on the route. Clamp before the square root.
        val clamped = a.coerceIn(0.0, 1.0)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(clamped), sqrt(1.0 - clamped))
    }

    fun initialBearingDegrees(a: GeoPoint, b: GeoPoint): Double =
        initialBearingDegrees(a.latitude, a.longitude, b.latitude, b.longitude)

    /** Great-circle initial bearing from `a` to `b`, in [0, 360). */
    fun initialBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)

        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        // atan2 handles the poles without a division by cos(latitude), so no special case is needed.
        return Math.toDegrees(atan2(y, x)).mod(360.0)
    }

    // ---------------------------------------------------------------- projection

    fun destination(from: GeoPoint, bearingDeg: Double, distanceMeters: Double): GeoPoint =
        destination(from.latitude, from.longitude, bearingDeg, distanceMeters)

    /** Point reached by travelling `distanceMeters` from (lat, lon) along `bearingDeg`. */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distanceMeters: Double): GeoPoint {
        val delta = distanceMeters / EARTH_RADIUS_M
        val theta = Math.toRadians(bearingDeg)
        val phi1 = Math.toRadians(lat)
        val lambda1 = Math.toRadians(lon)

        val sinPhi2 = sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta)
        val phi2 = asin(sinPhi2.coerceIn(-1.0, 1.0))
        val lambda2 = lambda1 + atan2(
            sin(theta) * sin(delta) * cos(phi1),
            cos(delta) - sin(phi1) * sinPhi2,
        )

        return GeoPoint(
            latitude = Math.toDegrees(phi2),
            // Crossing the antimeridian legitimately yields |λ| > 180. Location accepts that, but
            // maps wrap and consecutive route legs appear to jump, so normalise it here.
            longitude = normalizeLongitude(Math.toDegrees(lambda2)),
            coordType = CoordType.WGS84,
        )
    }

    /**
     * Great-circle interpolation between [from] and [to].
     *
     * `fraction` is not clamped, so values outside [0, 1] extrapolate along the same great circle.
     *
     * Spherical rather than linear in lat/lng: not for accuracy — over one tick the difference is
     * sub-millimetre — but because linear interpolation visibly cuts the corner on a long leg,
     * which is exactly what a sparsely sampled imported GPX track produces.
     */
    fun interpolate(from: GeoPoint, to: GeoPoint, fraction: Double): GeoPoint {
        val phi1 = Math.toRadians(from.latitude)
        val lambda1 = Math.toRadians(from.longitude)
        val phi2 = Math.toRadians(to.latitude)
        val lambda2 = Math.toRadians(to.longitude)

        val dPhi = phi2 - phi1
        val dLambda = lambda2 - lambda1
        val sinHalfPhi = sin(dPhi / 2)
        val sinHalfLambda = sin(dLambda / 2)
        val a = sinHalfPhi * sinHalfPhi + cos(phi1) * cos(phi2) * sinHalfLambda * sinHalfLambda
        val clamped = a.coerceIn(0.0, 1.0)
        val delta = 2 * atan2(sqrt(clamped), sqrt(1.0 - clamped))

        // Coincident endpoints make sin(delta) zero and the slerp weights 0/0.
        if (delta < 1e-9) return from

        // Antipodal endpoints: the great circle is not unique, so slerp is undefined. A plain
        // lerp is as good as anything and at least well-defined.
        if (delta > PI - 1e-9) {
            return GeoPoint(
                latitude = from.latitude + (to.latitude - from.latitude) * fraction,
                longitude = normalizeLongitude(
                    from.longitude + (to.longitude - from.longitude) * fraction,
                ),
                coordType = CoordType.WGS84,
            )
        }

        val sinDelta = sin(delta)
        val w1 = sin((1 - fraction) * delta) / sinDelta
        val w2 = sin(fraction * delta) / sinDelta

        val x = w1 * cos(phi1) * cos(lambda1) + w2 * cos(phi2) * cos(lambda2)
        val y = w1 * cos(phi1) * sin(lambda1) + w2 * cos(phi2) * sin(lambda2)
        val z = w1 * sin(phi1) + w2 * sin(phi2)

        return GeoPoint(
            latitude = Math.toDegrees(atan2(z, sqrt(x * x + y * y))),
            longitude = normalizeLongitude(Math.toDegrees(atan2(y, x))),
            coordType = CoordType.WGS84,
        )
    }

    // ---------------------------------------------------------------- normalisation

    /** Floored modulo, so negatives wrap forward: -10 becomes 350. */
    fun normalizeBearing(deg: Double): Double = deg.mod(360.0)

    /** Wraps a longitude into [-180, 180). */
    fun normalizeLongitude(deg: Double): Double = ((deg + 180.0).mod(360.0)) - 180.0

    /**
     * Signed shortest rotation from `fromDeg` to `toDeg`, in (-180, 180].
     *
     * Positive means clockwise. Note this is the right primitive for smoothing a heading: taking
     * the raw difference across the 359°/0° boundary yields a near-full turn instead of a 1° nudge.
     */
    fun shortestAngleDelta(fromDeg: Double, toDeg: Double): Double {
        val raw = (toDeg - fromDeg).mod(360.0)
        return if (raw > 180.0) raw - 360.0 else raw
    }

    // ---------------------------------------------------------------- small offsets

    fun metersToLatDegrees(meters: Double): Double = meters / METERS_PER_DEGREE_LAT

    /** Longitude degrees for a metre offset at a given latitude; shrinks towards the poles. */
    fun metersToLngDegrees(meters: Double, atLatDeg: Double): Double =
        meters / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(atLatDeg)).coerceAtLeast(1e-9))

    /** Offset a point by a metre delta, treating the delta as a local tangent-plane vector. */
    fun offsetMeters(from: GeoPoint, eastMeters: Double, northMeters: Double): GeoPoint =
        GeoPoint(
            latitude = from.latitude + metersToLatDegrees(northMeters),
            longitude = normalizeLongitude(
                from.longitude + metersToLngDegrees(eastMeters, from.latitude),
            ),
            coordType = CoordType.WGS84,
        )
}
