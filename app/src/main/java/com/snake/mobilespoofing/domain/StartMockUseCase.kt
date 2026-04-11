package com.snake.mobilespoofing.domain

import android.content.Context
import com.snake.mobilespoofing.location.CoordType
import com.snake.mobilespoofing.location.GeoPoint
import com.snake.mobilespoofing.service.MockLocationService

class StartMockUseCase(private val context: Context) {
    operator fun invoke(point: GeoPoint) {
        val normalized = point.copy(coordType = point.coordType.ifUnknownUseGcj())
        context.startForegroundService(MockLocationService.startIntent(context, normalized))
    }
}

private fun CoordType.ifUnknownUseGcj(): CoordType = when (this) {
    CoordType.WGS84, CoordType.GCJ02, CoordType.BD09 -> this
}
