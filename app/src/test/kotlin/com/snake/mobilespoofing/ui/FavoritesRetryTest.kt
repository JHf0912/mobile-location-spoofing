package com.snake.mobilespoofing.ui

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Backoff schedule and bounded-stop behavior for the favorites stream. */
class FavoritesRetryTest {

    @Test
    fun `the attempt budget is one initial subscription plus three retries`() {
        assertEquals(4, FavoritesRetry.MAX_ATTEMPTS)
    }

    @Test
    fun `backoff covers every configured retry`() {
        assertEquals(
            listOf(500L, 1_000L, 2_000L),
            (1 until FavoritesRetry.MAX_ATTEMPTS).map(FavoritesRetry::backoffMs),
        )
    }

    @Test
    fun `the schedule stays capped for larger attempt numbers`() {
        assertEquals(4_000L, FavoritesRetry.backoffMs(4))
        assertEquals(4_000L, FavoritesRetry.backoffMs(99))
    }

    @Test
    fun `an emit-then-throw flow still reaches the bounded limit`() = runTest {
        // The old loop reset its failure counter on every emission, so this flow retried forever.
        var subscriptions = 0
        val values = mutableListOf<List<String>>()
        val failureAttempts = mutableListOf<Int>()
        var exhausted: Throwable? = null
        val backoffs = mutableListOf<Long>()

        // Mirrors the ViewModel wiring: a visible list, a first-failure message, and the
        // exhaustion hook clearing the list and reporting the final error.
        var visibleFavorites = listOf("stale")
        var message: String? = null

        collectFavoritesWithRetry(
            source = {
                subscriptions++
                flow {
                    emit(listOf("row-$subscriptions"))
                    throw IOException("corrupt database")
                }
            },
            onValue = { favorites ->
                values += favorites
                visibleFavorites = favorites
            },
            onFailure = { attempt, error ->
                failureAttempts += attempt
                if (attempt == 1) message = "load failed: ${error.message}"
            },
            onExhausted = { error ->
                exhausted = error
                visibleFavorites = emptyList()
                message = "gave up: ${error.message}"
            },
            backoff = { backoffs += FavoritesRetry.backoffMs(it) },
        )

        assertEquals(FavoritesRetry.MAX_ATTEMPTS, subscriptions)
        assertEquals(listOf(1, 2, 3, 4), failureAttempts)
        // Every configured backoff step is used, then the helper stops without waiting again.
        assertEquals(listOf(500L, 1_000L, 2_000L), backoffs)
        assertEquals(4, values.size)
        assertTrue(exhausted is IOException)
        // The bounded stop clears the stale list and leaves a user-visible final message.
        assertEquals(emptyList<String>(), visibleFavorites)
        assertEquals("gave up: corrupt database", message)
    }

    @Test
    fun `a failed subscription is retried and then succeeds`() = runTest {
        var subscriptions = 0
        val values = mutableListOf<Int>()
        var exhausted = false

        collectFavoritesWithRetry(
            source = {
                subscriptions++
                if (subscriptions == 1) flow { throw IOException("transient") } else flowOf(7)
            },
            onValue = { values += it },
            onFailure = { _, _ -> },
            onExhausted = { exhausted = true },
            // Do not advance virtual time; the retry itself is the behavior under test.
            backoff = {},
        )

        assertEquals(2, subscriptions)
        assertEquals(listOf(7), values)
        assertFalse(exhausted)
    }

    @Test
    fun `a healthy flow is subscribed once and never retried`() = runTest {
        var subscriptions = 0
        val values = mutableListOf<List<String>>()
        var failures = 0
        var exhausted = false

        collectFavoritesWithRetry(
            source = {
                subscriptions++
                flowOf(listOf("a"), listOf("b"))
            },
            onValue = { values += it },
            onFailure = { _, _ -> failures++ },
            onExhausted = { exhausted = true },
            backoff = {},
        )

        assertEquals(1, subscriptions)
        assertEquals(listOf(listOf("a"), listOf("b")), values)
        assertEquals(0, failures)
        assertFalse(exhausted)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancellation propagates and never exhausts the retry budget`() = runTest {
        var failures = 0
        var exhausted = false
        val job = launch {
            collectFavoritesWithRetry(
                source = { flow<Unit> { awaitCancellation() } },
                onValue = {},
                onFailure = { _, _ -> failures++ },
                onExhausted = { exhausted = true },
                backoff = {},
            )
        }

        runCurrent()
        job.cancel()

        assertTrue(job.isCancelled)
        assertEquals(0, failures)
        assertFalse(exhausted)
    }
}
