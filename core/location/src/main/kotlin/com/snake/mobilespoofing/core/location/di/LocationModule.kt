package com.snake.mobilespoofing.core.location.di

import android.content.Context
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.snake.mobilespoofing.core.location.FusedMockLocationEngine
import com.snake.mobilespoofing.core.location.MockLocationEngine
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
     * Bound with an explicit parameter rather than `@Binds @IntoSet` plus a `Set<MockLocationEngine>`.
     *
     * Once there is a composite engine that both is a `MockLocationEngine` and consumes a set of
     * them, the set-based form is a Dagger dependency cycle — a confusing compile error. Explicit
     * parameters cannot form one, and adding a second engine here is a one-line change.
     */
    @Provides
    @Singleton
    fun mockLocationEngine(fused: FusedMockLocationEngine): MockLocationEngine = fused
}
