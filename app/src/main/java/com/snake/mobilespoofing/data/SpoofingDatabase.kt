package com.snake.mobilespoofing.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [FavoriteLocationEntity::class], version = 1, exportSchema = false)
abstract class SpoofingDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        fun create(context: Context): SpoofingDatabase {
            return Room.databaseBuilder(
                context,
                SpoofingDatabase::class.java,
                "spoofing.db"
            ).build()
        }
    }
}
