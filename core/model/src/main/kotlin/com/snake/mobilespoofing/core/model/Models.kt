package com.snake.mobilespoofing.core.model

/** Which geodetic datum a coordinate pair is expressed in. */
enum class CoordType {
    WGS84,
    GCJ02,
    BD09,
}

/**
 * A geographic position together with the datum it is expressed in.
 *
 * Note that this is not necessarily WGS84 — UI surfaces hand back GCJ02 (that is what AMap
 * renders), while anything handed to the location injectors must already be WGS84. Keep track of
 * which is which; the two differ by hundreds of metres.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val coordType: CoordType = CoordType.GCJ02,
)

/** Lifecycle of a mock-location session. */
sealed interface MockStatus {
    data object Idle : MockStatus

    data class Running(
        val pointWgs84: GeoPoint,
        val timestampMs: Long,
    ) : MockStatus

    data class Error(val message: String) : MockStatus
}
