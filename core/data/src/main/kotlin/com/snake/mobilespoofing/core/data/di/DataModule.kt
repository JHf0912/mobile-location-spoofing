package com.snake.mobilespoofing.core.data.di

import android.content.Context
import com.snake.mobilespoofing.core.data.FavoriteDao
import com.snake.mobilespoofing.core.data.SpoofingDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Declared in the module that owns these types rather than centralised in `:app`. A library module
 * cannot see the app, so a centralised setup would force every implementation class to be public —
 * exactly the coupling the module split exists to remove.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): SpoofingDatabase =
        SpoofingDatabase.create(context)

    @Provides
    fun favoriteDao(database: SpoofingDatabase): FavoriteDao = database.favoriteDao()
}
