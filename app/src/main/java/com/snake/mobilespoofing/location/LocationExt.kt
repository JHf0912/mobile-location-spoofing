package com.snake.mobilespoofing.location

import android.location.Location

fun GeoPoint.toLocation(provider: String = "fused"): Location {
    return Location(provider).apply {
        latitude = this@toLocation.latitude
        longitude = this@toLocation.longitude
        time = System.currentTimeMillis()
        accuracy = 3.0f
        elapsedRealtimeNanos = System.nanoTime()
    }
}
