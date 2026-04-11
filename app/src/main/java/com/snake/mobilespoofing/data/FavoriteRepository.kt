package com.snake.mobilespoofing.data

import com.snake.mobilespoofing.location.CoordType
import com.snake.mobilespoofing.location.GeoPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FavoriteRepository(private val dao: FavoriteDao) {
    fun observeAll(): Flow<List<FavoriteItem>> {
        return dao.observeAll().map { list ->
            list.map {
                FavoriteItem(
                    id = it.id,
                    name = it.name,
                    point = GeoPoint(it.latitude, it.longitude, CoordType.valueOf(it.coordType))
                )
            }
        }
    }

    suspend fun save(name: String, point: GeoPoint) {
        dao.insert(
            FavoriteLocationEntity(
                name = name,
                latitude = point.latitude,
                longitude = point.longitude,
                coordType = point.coordType.name
            )
        )
    }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
    }
}

data class FavoriteItem(
    val id: Long,
    val name: String,
    val point: GeoPoint
)
