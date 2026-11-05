package com.snake.mobilespoofing.core.location

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serializes cold-start provider cleanup against a service start.
 *
 * There are two different locks here and they must not be nested:
 *
 *  - [startGate] is a process-wide, non-reentrant mutex around the *decision* to run cleanup. It
 *    is held by the cleanup job and, briefly, by [markServiceStarting]. The service never holds it
 *    while waiting for [finishCleanup].
 *  - [ProviderLeaseStore.withLeaseLock] is a short mutex around the lease storage only. The engine
 *    takes it for the write that precedes `addTestProvider`; it is never taken inside [startGate].
 *
 * The service calls [markServiceStarting] and then [finishCleanup] before starting the runner. That
 * join is what guarantees cleanup and provider registration never overlap. If the cleanup had
 * already started, the service waits for it; if it had not, the flag makes it skip, because the
 * engine removes stale providers before registering new ones anyway.
 *
 * No method here blocks the main thread: [markServiceStarting] suspends on the gate and
 * [finishCleanup] suspends on the join, both bounded by the caller's timeout.
 */
class AppStartupCoordinator(
    private val leaseStore: ProviderLeaseStore,
    private val locationManagerEngine: LocationManagerMockEngine,
    private val packageName: String,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val startGate = Mutex()

    @Volatile
    private var serviceStarting = false

    /** The cleanup job the service joins. Exposed for diagnostics and tests. */
    val cleanupJob: Job = scope.launch(dispatcher) { runCleanup() }

    /**
     * Marks a service start before cleanup runs. Acquiring the gate waits for a cleanup that is
     * already in progress, so the caller knows cleanup has either finished or will be skipped.
     */
    suspend fun markServiceStarting() {
        startGate.withLock {
            serviceStarting = true
        }
    }

    /**
     * Waits at most [timeoutMs] for cleanup. Returns false if it did not finish, in which case the
     * caller must not register providers: cleanup may still be manipulating the same global
     * provider names.
     */
    suspend fun finishCleanup(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { cleanupJob.join() } != null

    private suspend fun runCleanup() {
        try {
            // Check before the gate: if the service wins the race, its engine will remove stale
            // providers itself as part of starting, and clearing them here would be redundant.
            if (serviceStarting) return

            startGate.withLock {
                if (serviceStarting) return@withLock
                clearStaleProviders()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort. The lease stays in place and the next cold start retries; a failed
            // cleanup must not keep the application from starting.
        }
    }

    private suspend fun clearStaleProviders() {
        val lease = leaseStore.peek() ?: return
        // Per design 4.3: an empty lease, a package mismatch or no providers means end directly.
        // A mismatched lease is left untouched rather than released: its providers may still be
        // registered, and a later cold start should see the same evidence.
        if (lease.packageName != packageName || lease.providers.isEmpty()) return

        val results = locationManagerEngine.clearStaleProviders(lease.providers)
        val removed = results.filter { it.removed }.map { it.provider }
        if (removed.isNotEmpty()) {
            // Only a confirmed removal may clear the lease; SecurityException leaves it for the
            // next cold start. A failed commit also leaves it on disk; the next cold start retries.
            leaseStore.release(removed)
        }
    }
}
