package com.snake.mobilespoofing

import android.app.Application
import com.google.android.gms.location.LocationServices
import com.snake.mobilespoofing.core.data.FavoriteRepository
import com.snake.mobilespoofing.core.data.SpoofingDatabase
import com.snake.mobilespoofing.core.geo.CoordinateTransformer
import com.snake.mobilespoofing.core.location.FusedMockLocationEngine
import com.snake.mobilespoofing.core.location.MockStatusStore
import com.snake.mobilespoofing.domain.StartMockUseCase
import com.snake.mobilespoofing.domain.StopMockUseCase

class SpoofingApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val db = SpoofingDatabase.create(this)
        val locationClient = LocationServices.getFusedLocationProviderClient(this)
        val engine = FusedMockLocationEngine(locationClient)
        val repo = FavoriteRepository(db.favoriteDao())
        val statusStore = MockStatusStore()
        container = AppContainer(
            favoriteRepository = repo,
            startMockUseCase = StartMockUseCase(this),
            stopMockUseCase = StopMockUseCase(this),
            transformer = CoordinateTransformer,
            statusStore = statusStore,
            engine = engine
        )
    }
}

data class AppContainer(
    val favoriteRepository: FavoriteRepository,
    val startMockUseCase: StartMockUseCase,
    val stopMockUseCase: StopMockUseCase,
    val transformer: CoordinateTransformer,
    val statusStore: MockStatusStore,
    val engine: FusedMockLocationEngine
)
