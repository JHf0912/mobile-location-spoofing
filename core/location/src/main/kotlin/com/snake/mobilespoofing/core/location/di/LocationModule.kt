package com.snake.mobilespoofing.core.location.di

import android.content.Context
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.snake.mobilespoofing.core.location.AndroidCurrentLocationSource
import com.snake.mobilespoofing.core.location.AndroidMockPermissionChecker
import com.snake.mobilespoofing.core.location.AndroidMockProviderController
import com.snake.mobilespoofing.core.location.CompositeMockLocationEngine
import com.snake.mobilespoofing.core.location.CurrentLocationSource
import com.snake.mobilespoofing.core.location.FusedLocationGateway
import com.snake.mobilespoofing.core.location.FusedMockLocationEngine
import com.snake.mobilespoofing.core.location.GmsFusedLocationGateway
import com.snake.mobilespoofing.core.location.LocationManagerMockEngine
import com.snake.mobilespoofing.core.location.LogEngineHealthReporter
import com.snake.mobilespoofing.core.location.MockLocationEngine
import com.snake.mobilespoofing.core.location.MockPermissionChecker
import com.snake.mobilespoofing.core.location.MockProviderController
import com.snake.mobilespoofing.core.location.MockSessionRunner
import com.snake.mobilespoofing.core.location.MockStatusStore
import com.snake.mobilespoofing.core.location.ProviderLeaseStore
import com.snake.mobilespoofing.core.location.SharedPreferencesProviderLeaseStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LocationModule {

    @Provides
    @Singleton
    fun fusedLocationProviderClient(
        @ApplicationContext context: Context,
    ): FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    @Provides
    @Singleton
    fun fusedLocationGateway(gateway: GmsFusedLocationGateway): FusedLocationGateway = gateway

    @Provides
    @Singleton
    fun mockPermissionChecker(checker: AndroidMockPermissionChecker): MockPermissionChecker = checker

    @Provides
    @Singleton
    fun currentLocationSource(source: AndroidCurrentLocationSource): CurrentLocationSource = source

    @Provides
    @Singleton
    fun providerLeaseStore(store: SharedPreferencesProviderLeaseStore): ProviderLeaseStore = store

    @Provides
    @Singleton
    fun mockProviderController(controller: AndroidMockProviderController): MockProviderController =
        controller

    /**
     * Assembled with explicit parameters rather than `@Binds @IntoSet` plus a `Set<MockLocationEngine>`.
     *
     * A composite that both is a `MockLocationEngine` and consumes a set of them is a Dagger
     * dependency cycle — a confusing compile error. Explicit parameters cannot form one, and adding
     * a third engine here stays a one-line change.
     *
     * Both engines run together so one session covers apps reading Google's fused client and apps
     * reading `LocationManager` directly. The LocationManager engine is **first** on purpose: it is
     * the reliable fallback, and must not wait for a fused-client Task that may never complete.
     */
    @Provides
    @Singleton
    fun mockLocationEngine(
        locationManager: LocationManagerMockEngine,
        fused: FusedMockLocationEngine,
    ): MockLocationEngine = CompositeMockLocationEngine(
        engines = listOf(locationManager, fused),
        // Failures only: a normal 1 Hz session must not flood logcat. This is the production
        // observation point for engine health (the lease is never driven by it).
        healthReporter = LogEngineHealthReporter,
    )

    /**
     * Built by hand rather than via `@Inject`: the clock and dispatcher are constructor parameters
     * with defaults specifically so tests can substitute them, and asking Dagger to supply a
     * `CoroutineDispatcher` would mean inventing a qualifier for no benefit.
     */
    @Provides
    @Singleton
    fun mockSessionRunner(
        engine: MockLocationEngine,
        statusStore: MockStatusStore,
        permissionChecker: MockPermissionChecker,
    ): MockSessionRunner = MockSessionRunner(
        engine = engine,
        statusStore = statusStore,
        permissionChecker = permissionChecker,
    )
}
