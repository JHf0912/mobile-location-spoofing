package com.snake.mobilespoofing.core.location.di

import android.content.Context
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.snake.mobilespoofing.core.location.CompositeMockLocationEngine
import com.snake.mobilespoofing.core.location.FusedMockLocationEngine
import com.snake.mobilespoofing.core.location.LocationManagerMockEngine
import com.snake.mobilespoofing.core.location.MockLocationEngine
import com.snake.mobilespoofing.core.location.MockSessionRunner
import com.snake.mobilespoofing.core.location.MockStatusStore
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

    /**
     * Assembled with explicit parameters rather than `@Binds @IntoSet` plus a `Set<MockLocationEngine>`.
     *
     * A composite that both is a `MockLocationEngine` and consumes a set of them is a Dagger
     * dependency cycle — a confusing compile error. Explicit parameters cannot form one, and adding
     * a third engine here stays a one-line change.
     *
     * Both engines run together so one session covers apps reading Google's fused client and apps
     * reading `LocationManager` directly. Either may fail without taking the other down.
     */
    @Provides
    @Singleton
    fun mockLocationEngine(
        fused: FusedMockLocationEngine,
        locationManager: LocationManagerMockEngine,
    ): MockLocationEngine = CompositeMockLocationEngine(listOf(fused, locationManager))

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
    ): MockSessionRunner = MockSessionRunner(engine, statusStore)
}
