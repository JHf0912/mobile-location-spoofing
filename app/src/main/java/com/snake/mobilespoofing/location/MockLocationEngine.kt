package com.snake.mobilespoofing.location

import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import kotlinx.coroutines.tasks.await

interface MockLocationEngine {
    suspend fun startMockMode()
    suspend fun push(location: Location)
    suspend fun stopMockMode()
}

class FusedMockLocationEngine(
    private val fusedLocationProviderClient: FusedLocationProviderClient
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
