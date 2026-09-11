package com.snake.mobilespoofing.core.location

import android.location.Location
import android.os.SystemClock
import com.snake.mobilespoofing.core.model.GeoPoint

private const val DEFAULT_ACCURACY_METERS = 3.0f

fun GeoPoint.toLocation(provider: String = "fused"): Location =
    Location(provider).apply {
        latitude = this@toLocation.latitude
        longitude = this@toLocation.longitude
        time = System.currentTimeMillis()
        accuracy = DEFAULT_ACCURACY_METERS
        // elapsedRealtimeNanos() is boot-relative; System.nanoTime() has an arbitrary origin.
        // Consumers compute fix age as SystemClock.elapsedRealtimeNanos() minus this value, so
        // mixing the two clocks produces garbage. Note this still stamps generation time rather
        // than injection time — the engine rework moves the stamp to the injection point.
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }
