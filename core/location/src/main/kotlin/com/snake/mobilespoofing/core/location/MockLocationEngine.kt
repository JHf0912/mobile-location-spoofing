package com.snake.mobilespoofing.core.location

import com.google.android.gms.location.FusedLocationProviderClient
import com.snake.mobilespoofing.core.model.Fix
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

/**
 * Injects mock positions into the platform.
 *
 * Takes a [Fix] rather than an `android.location.Location`: `Location` is mutable, so handing one
 * instance to several engines lets them overwrite each other's `provider` field. Each engine builds
 * its own from the fix instead.
 *
 * Implementations must treat the two lifecycle calls as paired — a `LocationManager` test provider
 * is registered system-wide and outlives this process, so a missed stop leaves the device
 * reporting a fake position to every app until it is rebooted.
 */
interface MockLocationEngine {
    suspend fun startMockMode()
    suspend fun push(fix: Fix)
    suspend fun stopMockMode()
}

/** Injects through Google's FusedLocationProviderClient. */
@Singleton
class FusedMockLocationEngine @Inject constructor(
    private val fusedLocationProviderClient: FusedLocationProviderClient,
) : MockLocationEngine {

    override suspend fun startMockMode() {
        fusedLocationProviderClient.setMockMode(true).await()
    }

    override suspend fun push(fix: Fix) {
        fusedLocationProviderClient.setMockLocation(fix.toLocation(PROVIDER)).await()
    }

    override suspend fun stopMockMode() {
        fusedLocationProviderClient.setMockMode(false).await()
    }

    private companion object {
        const val PROVIDER = "fused"
    }
}
