package com.snake.mobilespoofing.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    // id is the tie-breaker: backups and rapid saves can share a createdAt millisecond, and without
    // a secondary key the order (and so the list jump on insert) is unspecified.
    @Query("SELECT * FROM favorite_locations ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<FavoriteLocationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FavoriteLocationEntity)

    @Query("DELETE FROM favorite_locations WHERE id = :id")
    suspend fun deleteById(id: Long)
}
