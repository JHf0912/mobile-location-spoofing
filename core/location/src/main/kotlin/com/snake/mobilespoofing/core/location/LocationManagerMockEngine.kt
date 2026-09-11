package com.snake.mobilespoofing.core.location

import android.content.Context
import android.location.Criteria
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import com.snake.mobilespoofing.core.model.Fix
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Injects through `LocationManager` test providers.
 *
 * This is the engine that covers apps reading location through `LocationManager` directly rather
 * than through Google's fused client — a gap that made the tool silently do nothing for them.
 *
 * ⚠️ Test providers are registered with the *system*, not with this process. If the app is killed
 * without `stopMockMode`, the device keeps reporting a mock position to every app on it, and the
 * only recovery is a reboot or another mock-location app clearing it. Every path that can leave
 * this class — including a crash — must therefore go through [stopMockMode], and [startMockMode]
 * clears leftovers before it registers anything.
 */
@Singleton
class LocationManagerMockEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : MockLocationEngine {

    private val locationManager: LocationManager
        get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val registered = mutableSetOf<String>()

    override suspend fun startMockMode() {
        val problems = mutableListOf<String>()

        PROVIDERS.forEach { provider ->
            try {
                // Always remove first. A provider left over from a previous run — or from a crash —
                // makes addTestProvider throw, and this is the only place that can clear it.
                removeTestProviderQuietly(provider)
                addTestProvider(provider)
                locationManager.setTestProviderEnabled(provider, true)
                registered += provider
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                // The app is not the system's selected mock-location app.
                problems += "$provider: not selected as the mock location app"
            } catch (e: IllegalArgumentException) {
                // The provider name is already taken, or is not one this device supports.
                problems += "$provider: provider unavailable (${e.message})"
            } catch (e: RuntimeException) {
                problems += "$provider: ${e::class.simpleName}: ${e.message}"
            }
        }

        if (registered.isEmpty()) {
            error("No location provider could be mocked. ${problems.joinToString("; ")}")
        }
    }

    override suspend fun push(fix: Fix) {
        val failures = mutableListOf<String>()
        registered.forEach { provider ->
            try {
                // A fresh Location per provider: API 31+ rejects a test location that is not
                // "complete", and each provider needs its own name stamped on it.
                locationManager.setTestProviderLocation(provider, fix.toLocation(provider))
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                failures += "$provider: ${e.message}"
            }
        }
        // A single failed push is a warning, not a session failure — one provider being flaky must
        // not stop the others from moving.
        if (registered.isNotEmpty() && failures.size == registered.size) {
            error("all providers rejected the fix. ${failures.joinToString("; ")}")
        }
    }

    override suspend fun stopMockMode() {
        // Runs even when startMockMode only partially succeeded, which is exactly when a leftover
        // provider is most likely.
        registered.toList().forEach { provider ->
            setTestProviderEnabledQuietly(provider)
            removeTestProviderQuietly(provider)
        }
        registered.clear()
    }

    // ---------------------------------------------------------------- provider plumbing

    private fun addTestProvider(provider: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val properties = ProviderProperties.Builder()
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(ProviderProperties.POWER_USAGE_HIGH)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .build()
            locationManager.addTestProvider(provider, properties)
        } else {
            // The single modern overload only exists from API 31. Everything older needs this one.
            @Suppress("DEPRECATION")
            locationManager.addTestProvider(
                provider,
                /* requiresNetwork = */ false,
                /* requiresSatellite = */ true,
                /* requiresCell = */ false,
                /* hasMonetaryCost = */ false,
                /* supportsAltitude = */ true,
                /* supportsSpeed = */ true,
                /* supportsBearing = */ true,
                Criteria.POWER_HIGH,
                Criteria.ACCURACY_FINE,
            )
        }
    }

    private fun removeTestProviderQuietly(provider: String) {
        try {
            locationManager.removeTestProvider(provider)
        } catch (_: IllegalArgumentException) {
            // Nothing registered under that name, which is the desired state anyway.
        }
    }

    private fun setTestProviderEnabledQuietly(provider: String) {
        try {
            locationManager.setTestProviderEnabled(provider, false)
        } catch (_: IllegalArgumentException) {
            // Already gone.
        } catch (_: SecurityException) {
            // Permission was revoked mid-session; removeTestProvider below still cleans up.
        }
    }

    companion object {
        /**
         * GPS and network both, because apps differ in which they ask for and mocking only one
         * leaves the other reporting the real position.
         */
        private val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
    }
}
