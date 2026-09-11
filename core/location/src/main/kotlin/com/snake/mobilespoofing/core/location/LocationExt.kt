package com.snake.mobilespoofing.core.location

import android.location.Location
import android.os.SystemClock
import com.snake.mobilespoofing.core.model.Fix

/**
 * Converts a [Fix] into the platform's `Location`.
 *
 * Stamped here, at the moment of injection, rather than when the fix was generated: a sample
 * produced early but pushed late would otherwise claim a freshness it does not have.
 *
 * `elapsedRealtimeNanos` uses the boot-relative clock that consumers subtract from when they
 * compute fix age. `System.nanoTime()` has an arbitrary origin and makes that subtraction garbage.
 */
fun Fix.toLocation(provider: String): Location = Location(provider).apply {
    latitude = this@toLocation.point.latitude
    longitude = this@toLocation.point.longitude
    accuracy = this@toLocation.accuracyMeters
    time = System.currentTimeMillis()
    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

    speed = this@toLocation.speedMetersPerSecond

    // A stationary receiver reports no heading at all rather than due north, so only set one when
    // the source says it is meaningful.
    if (this@toLocation.hasBearing) {
        bearing = this@toLocation.bearingDegrees
    } else {
        removeBearing()
    }

    this@toLocation.altitudeMeters?.let { altitude = it }
    this@toLocation.verticalAccuracyMeters?.let { verticalAccuracyMeters = it }
}
