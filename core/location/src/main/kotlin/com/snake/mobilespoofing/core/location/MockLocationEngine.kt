package com.snake.mobilespoofing.core.location

import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import kotlinx.coroutines.tasks.await

/**
 * Injects mock positions into the platform.
 *
 * Implementations must treat `startMockMode`/`stopMockMode` as paired: a test provider left
 * registered outlives the process, so a missed stop keeps the device reporting a fake position to
 * every app until it is rebooted.
 */
interface MockLocationEngine {
    suspend fun startMockMode()
    suspend fun push(location: Location)
    suspend fun stopMockMode()
}

/** Injects through Google's FusedLocationProviderClient. */
class FusedMockLocationEngine(
    private val fusedLocationProviderClient: FusedLocationProviderClient,
) : MockLocationEngine {

    override suspend fun startMockMode() {
        fusedLocationProviderClient.setMockMode(true).await()
    }

    override suspend fun push(location: Location) {
        fusedLocationProviderClient.setMockLocation(location).await()
    }

    override suspend fun stopMockMode() {
        fusedLocationProviderClient.setMockMode(false).await()
    }
}
