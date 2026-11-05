package com.snake.mobilespoofing.ui

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * Bounded retry policy for the favorites stream.
 *
 * A Room flow does not fail by itself; a corrupt database or an I/O error terminates the
 * subscription. Retrying a few times with a short backoff rides out transient errors, and the
 * ViewModel clears the visible list if every attempt fails, so the screen cannot sit on stale rows
 * with no indication that updates stopped.
 */
internal object FavoritesRetry {
    /**
     * One initial subscription plus three retries. The three backoff steps below are all used:
     * 500 ms, 1 s and 2 s. The counter is deliberately *not* reset by an emission, so this is a
     * conservative budget for the whole ViewModel lifetime: an emit-then-throw flow cannot retry
     * forever, at the cost of a later, unrelated error having fewer retries remaining.
     */
    const val MAX_ATTEMPTS = 4

    private const val INITIAL_BACKOFF_MS = 500L
    private const val MAX_BACKOFF_SHIFT = 3

    /** Delay before retrying after [failedAttempt] (1-based). */
    fun backoffMs(failedAttempt: Int): Long =
        INITIAL_BACKOFF_MS shl (failedAttempt - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
}

/**
 * Collects [source] with [FavoritesRetry.MAX_ATTEMPTS] consecutive failed subscriptions.
 *
 * [onFailure] receives every failed attempt (so the ViewModel can surface the first one), and
 * [onExhausted] runs once before the helper returns after the final failure. A flow that completes
 * normally returns without retrying. Cancellation is never swallowed.
 */
internal suspend fun <T> collectFavoritesWithRetry(
    source: () -> Flow<T>,
    onValue: (T) -> Unit,
    onFailure: (attempt: Int, error: Throwable) -> Unit,
    onExhausted: (error: Throwable) -> Unit,
    maxAttempts: Int = FavoritesRetry.MAX_ATTEMPTS,
    backoff: suspend (failedAttempt: Int) -> Unit = { delay(FavoritesRetry.backoffMs(it)) },
) {
    var failedAttempts = 0
    while (true) {
        try {
            source().collect { value -> onValue(value) }
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedAttempts++
            onFailure(failedAttempts, e)
            if (failedAttempts >= maxAttempts) {
                onExhausted(e)
                return
            }
            backoff(failedAttempts)
        }
    }
}
