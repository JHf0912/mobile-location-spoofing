package com.snake.mobilespoofing.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The device's real position, used to seed the editor when the app starts.
 *
 * Backed by [LocationManager] rather than the fused client on purpose: this has to work on
 * devices without Google Play services, and a coarse fix is enough to centre the picker. The
 * result is WGS84, which is what [GeoPoint] carries into the editor.
 */
fun interface CurrentLocationSource {
    /** A cached or freshly acquired fix, or null when location is unavailable/denied. */
    suspend fun current(): GeoPoint?
}

@Singleton
class AndroidCurrentLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : CurrentLocationSource {

    override suspend fun current(): GeoPoint? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) return@withContext null
        val manager = context.getSystemService(LocationManager::class.java) ?: return@withContext null
        val providers = PROVIDERS.filter { provider -> isEnabled(manager, provider) }
        if (providers.isEmpty()) return@withContext null

        // A cached fix is instant and good enough to seed the map; waiting for a fresh one indoors
        // can take the whole timeout and still return nothing.
        lastKnown(manager, providers) ?: requestFresh(manager, providers)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    private fun isEnabled(manager: LocationManager, provider: String): Boolean =
        runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)

    // Safe: current() returns early unless hasLocationPermission() is true, and providers is only
    // built after that guard. Lint cannot see across the call, so the check is stated explicitly.
    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager, providers: List<String>): GeoPoint? =
        providers
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
            ?.let(::toGeoPoint)

    private suspend fun requestFresh(manager: LocationManager, providers: List<String>): GeoPoint? {
        for (provider in providers) {
            val location = withTimeoutOrNull(FIX_TIMEOUT_MS) { awaitCurrent(manager, provider) }
            if (location != null) return toGeoPoint(location)
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitCurrent(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            runCatching {
                LocationManagerCompat.getCurrentLocation(
                    manager,
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(context),
                ) { location -> continuation.resume(location) }
            }.onFailure {
                continuation.resume(null)
            }
        }

    private fun toGeoPoint(location: Location): GeoPoint =
        GeoPoint(location.latitude, location.longitude, CoordType.WGS84)

    private companion object {
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        const val FIX_TIMEOUT_MS = 8_000L
    }
}
