package com.snake.mobilespoofing.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import com.snake.mobilespoofing.core.model.Fix
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Narrow seam over the `LocationManager` test-provider calls.
 *
 * Extracted so [LocationManagerMockEngine]'s bookkeeping (lease-before-add, per-provider stop
 * results, re-registration) can be tested with virtual time on the JVM — `Location` and
 * `LocationManager` are Android classes that throw "Stub!" in local unit tests. The production
 * implementation is [AndroidMockProviderController].
 */
interface MockProviderController {
    fun addProvider(provider: String)

    fun setProviderEnabled(provider: String, enabled: Boolean)

    fun removeProvider(provider: String)

    fun setProviderLocation(provider: String, fix: Fix)
}

@Singleton
class AndroidMockProviderController @Inject constructor(
    @ApplicationContext private val context: Context,
) : MockProviderController {

    private val locationManager: LocationManager
        get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // ProviderProperties.POWER_USAGE_* / ACCURACY_* are byte constants in the API 37 stubs;
    // lint widens them to Int and then reports a WrongConstant false positive against @IntDef.
    @SuppressLint("WrongConstant")
    override fun addProvider(provider: String) {
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

    override fun setProviderEnabled(provider: String, enabled: Boolean) {
        locationManager.setTestProviderEnabled(provider, enabled)
    }

    override fun removeProvider(provider: String) {
        locationManager.removeTestProvider(provider)
    }

    override fun setProviderLocation(provider: String, fix: Fix) {
        // A fresh Location per provider: API 31+ rejects a test location that is not "complete",
        // and each provider needs its own name stamped on it.
        locationManager.setTestProviderLocation(provider, fix.toLocation(provider))
    }
}
