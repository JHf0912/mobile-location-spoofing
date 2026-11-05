package com.snake.mobilespoofing.core.location

import com.snake.mobilespoofing.core.model.Fix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the two-level gate: cleanup holds the startup gate, a service start marks itself and
 * joins, and the engine's lease lock is never nested inside the gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppStartupCoordinatorTest {

    private val controller = RecordingController()

    private fun engine(store: ProviderLeaseStore) = LocationManagerMockEngine(
        providerController = controller,
        permissionChecker = MockPermissionChecker { true },
        leaseStore = store,
    )

    @Test
    fun `a service start waits for an in-progress cleanup without deadlocking`() = runTest {
        val store = BlockingLeaseStore(
            lease = ProviderLease(1L, 0L, setOf("gps"), PACKAGE),
            blockPeek = true,
        )
        val coordinator = AppStartupCoordinator(
            leaseStore = store,
            locationManagerEngine = engine(store),
            packageName = PACKAGE,
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        assertTrue(store.peekEntered.isCompleted)

        var marked = false
        launch {
            coordinator.markServiceStarting()
            marked = true
        }
        runCurrent()
        // The cleanup holds the startup gate; the service start must wait rather than proceed.
        assertFalse(marked)

        store.allowPeek.complete(Unit)
        runCurrent()

        assertTrue(marked)
        assertTrue(coordinator.finishCleanup(1_000L))
        assertEquals(listOf("gps"), controller.removed)
        assertEquals(listOf(setOf("gps")), store.releases)
        assertTrue(coordinator.cleanupJob.isCompleted)
    }

    @Test
    fun `a service start that wins makes cleanup skip stale removal`() = runTest {
        val store = BlockingLeaseStore(
            lease = ProviderLease(1L, 0L, setOf("gps"), PACKAGE),
            blockPeek = false,
        )
        val coordinator = AppStartupCoordinator(
            leaseStore = store,
            locationManagerEngine = engine(store),
            packageName = PACKAGE,
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        coordinator.markServiceStarting()
        assertTrue(coordinator.finishCleanup(1_000L))

        // The service's own engine start removes leftovers before registering, so cleanup can
        // safely skip when the service is already starting.
        assertEquals(emptyList<String>(), controller.removed)
        assertEquals(emptyList<Set<String>>(), store.releases)
    }

    @Test
    fun `a lease from another package is left untouched`() = runTest {
        // Design 4.3: empty lease, package mismatch or no providers means end directly. Releasing
        // would forget providers that may still be registered under a renamed build.
        val store = BlockingLeaseStore(
            lease = ProviderLease(1L, 0L, setOf("gps"), "other.package"),
            blockPeek = false,
        )
        val coordinator = AppStartupCoordinator(
            leaseStore = store,
            locationManagerEngine = engine(store),
            packageName = PACKAGE,
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertTrue(coordinator.finishCleanup(1_000L))

        assertEquals(emptyList<String>(), controller.removed)
        assertEquals(emptyList<Set<String>>(), store.releases)
    }

    @Test
    fun `finishCleanup reports a timeout instead of waiting forever`() = runTest {
        val store = BlockingLeaseStore(
            lease = ProviderLease(1L, 0L, setOf("gps"), PACKAGE),
            blockPeek = true,
        )
        val coordinator = AppStartupCoordinator(
            leaseStore = store,
            locationManagerEngine = engine(store),
            packageName = PACKAGE,
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        assertTrue(store.peekEntered.isCompleted)

        assertFalse(coordinator.finishCleanup(100L))

        // Let the background cleanup finish so the test does not leave a suspended job behind.
        store.allowPeek.complete(Unit)
        runCurrent()
    }

    @Test
    fun `a provider that cannot be confirmed removed stays leased`() = runTest {
        controller.removeFailure = SecurityException("revoked")
        val store = BlockingLeaseStore(
            lease = ProviderLease(1L, 0L, setOf("gps"), PACKAGE),
            blockPeek = false,
        )
        val coordinator = AppStartupCoordinator(
            leaseStore = store,
            locationManagerEngine = engine(store),
            packageName = PACKAGE,
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertTrue(coordinator.finishCleanup(1_000L))

        assertEquals(emptyList<Set<String>>(), store.releases)
    }

    private class BlockingLeaseStore(
        var lease: ProviderLease?,
        var blockPeek: Boolean,
    ) : ProviderLeaseStore {
        val peekEntered = CompletableDeferred<Unit>()
        val allowPeek = CompletableDeferred<Unit>()
        val releases = mutableListOf<Set<String>>()

        override suspend fun <T> withLeaseLock(block: suspend () -> T): T = block()

        override suspend fun peek(): ProviderLease? {
            if (blockPeek) {
                peekEntered.complete(Unit)
                allowPeek.await()
            }
            return lease
        }

        override suspend fun acquire(sessionId: Long, providers: Collection<String>): Boolean = true

        override suspend fun release(providers: Collection<String>): Boolean {
            releases += providers.toSet()
            return true
        }
    }

    private class RecordingController : MockProviderController {
        val removed = mutableListOf<String>()
        var removeFailure: Exception? = null

        override fun addProvider(provider: String) = Unit

        override fun setProviderEnabled(provider: String, enabled: Boolean) = Unit

        override fun removeProvider(provider: String) {
            removeFailure?.let { throw it }
            removed += provider
        }

        override fun setProviderLocation(provider: String, fix: Fix) = Unit
    }

    private companion object {
        const val PACKAGE = "com.snake.mobilespoofing"
    }
}
