package com.snake.mobilespoofing.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs against the real Android SQLite implementation, unlike the JVM tests in this module.
 *
 * It is the only test that proves the Room-generated schema, the auto-generated primary key and
 * the invalidation-tracked Flow actually work at runtime.
 */
@RunWith(AndroidJUnit4::class)
class SpoofingDatabaseTest {

    private lateinit var database: SpoofingDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SpoofingDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun daoInsertsOrdersAndDeletes() = runBlocking {
        val dao = database.favoriteDao()
        dao.insert(
            FavoriteLocationEntity(
                name = "older",
                latitude = 1.0,
                longitude = 2.0,
                coordType = "WGS84",
                createdAt = 1L,
            ),
        )
        dao.insert(
            FavoriteLocationEntity(
                name = "newer",
                latitude = 3.0,
                longitude = 4.0,
                coordType = "WGS84",
                createdAt = 2L,
            ),
        )

        val rows = dao.observeAll().first()
        assertEquals(listOf("newer", "older"), rows.map { it.name })
        // autoGenerate: id=0 in the entity becomes NULL in SQL and SQLite assigns the rowid.
        assertTrue(rows.all { it.id > 0 })

        dao.deleteById(rows.first { it.name == "older" }.id)
        assertEquals(listOf("newer"), dao.observeAll().first().map { it.name })
    }

    @Test
    fun daoOrdersByCreatedAtThenIdDescending() = runBlocking {
        val dao = database.favoriteDao()
        // Same createdAt millisecond: the id tie-breaker must decide, newest row first.
        listOf("first", "second", "third").forEach { name ->
            dao.insert(
                FavoriteLocationEntity(
                    name = name,
                    latitude = 1.0,
                    longitude = 2.0,
                    coordType = "WGS84",
                    createdAt = 5L,
                ),
            )
        }

        assertEquals(
            listOf("third", "second", "first"),
            dao.observeAll().first().map { it.name },
        )
    }

    @Test
    fun repositoryRoundTripsAFavorite() = runBlocking {
        val repository = FavoriteRepository(database.favoriteDao())
        val point = GeoPoint(39.9042, 116.4074, CoordType.WGS84)

        repository.save("office", point)
        val items = repository.observeAll().first()

        assertEquals(1, items.size)
        assertEquals("office", items.single().name)
        assertEquals(point, items.single().point)
    }

    @Test
    fun fileBackedDatabaseOpensAndPersists() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DATABASE_NAME)
        val fileDatabase = SpoofingDatabase.create(context)
        try {
            fileDatabase.favoriteDao().insert(
                FavoriteLocationEntity(
                    name = "persisted",
                    latitude = 39.9042,
                    longitude = 116.4074,
                    coordType = "WGS84",
                ),
            )
            val rows = fileDatabase.favoriteDao().observeAll().first()
            assertEquals(listOf("persisted"), rows.map { it.name })
        } finally {
            fileDatabase.close()
            context.deleteDatabase(DATABASE_NAME)
        }
    }

    private companion object {
        // Must match SpoofingDatabase.create(...), which is the builder the app actually uses.
        const val DATABASE_NAME = "spoofing.db"
    }
}
