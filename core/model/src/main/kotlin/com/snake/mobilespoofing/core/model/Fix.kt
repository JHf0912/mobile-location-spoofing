package com.snake.mobilespoofing.core.model

/**
 * One location sample, ready to be injected.
 *
 * Always WGS84: both injectors require it, and route geometry is computed in that frame so the
 * reported speed and bearing match the path actually travelled.
 *
 * Deliberately carries no timestamp. A fix describes a *position*, not a moment — the moment is
 * stamped when it is handed to the platform, otherwise a sample generated early but pushed late
 * would claim a freshness it does not have.
 */
data class Fix(
    val point: GeoPoint,
    val altitudeMeters: Double? = null,
    val speedMetersPerSecond: Float = 0f,
    val bearingDegrees: Float = 0f,
    /**
     * Whether [bearingDegrees] is meaningful. Real receivers report no heading until they have
     * moved, so a stationary fix should leave this false rather than claiming due north.
     */
    val hasBearing: Boolean = false,
    val accuracyMeters: Float = DEFAULT_ACCURACY_METERS,
    val verticalAccuracyMeters: Float? = null,
) {
    companion object {
        const val DEFAULT_ACCURACY_METERS: Float = 3f
    }
}
