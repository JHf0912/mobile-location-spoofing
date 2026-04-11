package com.snake.mobilespoofing.location

enum class CoordType {
    WGS84,
    GCJ02,
    BD09
}

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val coordType: CoordType = CoordType.GCJ02
)

sealed interface MockStatus {
    data object Idle : MockStatus
    data class Running(val pointWgs84: GeoPoint, val timestampMs: Long) : MockStatus
    data class Error(val message: String) : MockStatus
}
