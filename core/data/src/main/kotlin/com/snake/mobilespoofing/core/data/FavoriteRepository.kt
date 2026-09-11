package com.snake.mobilespoofing.core.data

import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.FavoriteItem
import com.snake.mobilespoofing.core.model.GeoPoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class FavoriteRepository @Inject constructor(private val dao: FavoriteDao) {

    fun observeAll(): Flow<List<FavoriteItem>> =
        dao.observeAll().map { rows ->
            rows.map { row ->
                FavoriteItem(
                    id = row.id,
                    name = row.name,
                    point = GeoPoint(
                        latitude = row.latitude,
                        longitude = row.longitude,
                        coordType = parseCoordType(row.coordType),
                    ),
                )
            }
        }

    suspend fun save(name: String, point: GeoPoint) {
        dao.insert(
            FavoriteLocationEntity(
                name = name,
                latitude = point.latitude,
                longitude = point.longitude,
                coordType = point.coordType.name,
            ),
        )
    }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
    }
}

/**
 * Deliberately forgiving.
 *
 * `CoordType.valueOf` throws on an unrecognised string, and this runs inside a `Flow.map` — so a
 * single corrupt row would terminate the favorites stream permanently and silently, leaving the
 * UI stuck on stale data with no error surfaced anywhere. Falling back to the app's default datum
 * degrades one row instead of the whole list.
 */
internal fun parseCoordType(raw: String?): CoordType =
    CoordType.entries.firstOrNull { it.name == raw } ?: CoordType.GCJ02
