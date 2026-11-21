package com.snake.mobilespoofing.core.location

import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.Fix
import com.snake.mobilespoofing.core.model.GeoPoint
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's bookkeeping is tested through the provider-controller seam, so no Robolectric and
 * no real `LocationManager` are needed. The system-level behaviour (whether removal really works)
 * is `needs-device`.
 */
class LocationManagerMockEngineTest {

    private val fix = Fix(GeoPoint(39.9042, 116.4074, CoordType.WGS84))

    private val log = mutableListOf<String>()

    private val controller = FakeProviderController(log)

    private val leaseStore = RecordingLeaseStore(log)

    private val permissionChecker = CountingPermissionChecker()

    private fun engine() = LocationManagerMockEngine(controller, permissionChecker, leaseStore)

    @Test
    fun `the lease is written for each provider before it is added`() = runTest {
        val engine = engine()

        assertEquals(EngineStartOutcome.Ok, engine.startMockMode(sessionId = 7L))

        assertEquals(7L, leaseStore.acquired.first().first)
        assertEquals(setOf("gps"), leaseStore.acquired[0].second)
        assertEquals(setOf("network"), leaseStore.acquired[1].second)

        // Write-before-add is the invariant that makes a process death recoverable.
        assertTrue(log.indexOf("acquire:gps") in 0 until log.indexOf("add:gps"))
        assertTrue(log.indexOf("acquire:network") in 0 until log.indexOf("add:network"))
        // Both writes must be inside withLeaseLock (F10: a pass-through fake used to hide a
        // missing wrapper).
        assertTrue(leaseStore.acquireUnderLock)
    }

    @Test
    fun `a failed lease write prevents the provider from being registered`() = runTest {
        leaseStore.failAcquire = true
        val engine = engine()

        val outcome = engine.startMockMode(sessionId = 1L)

        assertTrue(outcome is EngineStartOutcome.Failed)
        assertTrue((outcome as EngineStartOutcome.Failed).detail.orEmpty().contains("lease write failed"))
        // I1: no durable lease, no provider registration.
        assertFalse(log.any { it.startsWith("add:") })
    }

    @Test
    fun `a lease failure after one provider rolled back the provider already added`() = runTest {
        // gps is leased and added, then the network lease write fails. The start must fail and
        // remove gps again: a provider must never outlive the lease that tracks it.
        leaseStore.failAcquireOn = "network"
        val engine = engine()

        val outcome = engine.startMockMode(sessionId = 1L)

        assertTrue(outcome is EngineStartOutcome.Failed)
        assertTrue((outcome as EngineStartOutcome.Failed).detail.orEmpty().contains("lease write failed"))
        // The provider was removed at the end of the start, after it had been added.
        assertTrue(log.lastIndexOf("remove:gps") > log.indexOf("add:gps"))
        assertEquals(emptySet<String>(), controller.added)
        // gps was confirmed removed, so its lease entry is released as well.
        assertEquals(listOf(setOf("gps")), leaseStore.releases)
    }

    @Test
    fun `start fails when no provider can be registered`() = runTest {
        controller.addFailures += listOf("gps", "network")
        val engine = engine()

        val outcome = engine.startMockMode(sessionId = 1L)

        assertTrue(outcome is EngineStartOutcome.Failed)
        assertEquals(emptyList<ProviderStopResult>(), engine.stopMockMode())
    }

    @Test
    fun `start succeeds when at least one provider is enabled`() = runTest {
        controller.addFailures += "gps"
        val engine = engine()

        assertEquals(EngineStartOutcome.Ok, engine.startMockMode(sessionId = 1L))

        // gps was leased (lease-before-add) and then addProvider threw, so nothing was ever
        // registered under it; its lease entry is released instead of leaking to the next cold
        // start (core-7). The acquire itself must still be on record.
        assertTrue(leaseStore.acquired.any { it.second == setOf("gps") })
        assertEquals(setOf("gps"), leaseStore.releases.single())

        val results = engine.stopMockMode()
        assertEquals(listOf("network"), results.map { it.provider })
        assertTrue(results.single().removed)
    }

    @Test
    fun `a start that enables nothing rolls back the providers it registered`() = runTest {
        // addProvider succeeds for both, but setProviderEnabled fails: the providers are touched
        // (registered) yet enabled stays empty. A failed start must roll them back instead of
        // leaving registered-but-disabled providers behind (core-5).
        controller.enableFailures += listOf("gps", "network")
        val engine = engine()

        val outcome = engine.startMockMode(sessionId = 1L)

        assertTrue(outcome is EngineStartOutcome.Failed)
        assertTrue(log.lastIndexOf("remove:gps") > log.indexOf("add:gps"))
        assertTrue(log.lastIndexOf("remove:network") > log.indexOf("add:network"))
        assertEquals(emptySet<String>(), controller.added)
        assertEquals(setOf("gps", "network"), leaseStore.releases.single().toSet())
        assertTrue(leaseStore.releaseUnderLock)
    }

    @Test
    fun `a failed registration releases the lease it wrote without a stop`() = runTest {
        // addProvider throws after the lease was written; the provider never enters `touched`, so a
        // later stop would not release it. The failure path itself must release the lease (core-7).
        controller.addFailures += listOf("gps", "network")
        val engine = engine()

        val outcome = engine.startMockMode(sessionId = 1L)

        assertTrue(outcome is EngineStartOutcome.Failed)
        assertEquals(setOf("gps", "network"), leaseStore.releases.flatten().toSet())
        assertTrue(leaseStore.releaseUnderLock)
        // Nothing was registered, so a stop has nothing left to remove.
        assertEquals(emptyList<ProviderStopResult>(), engine.stopMockMode())
    }

    @Test
    fun `a cancellation after the lease write releases that provider`() = runTest {
        // Simulates cancellation observed after acquire's commit wrote the lease but before it
        // returned: the entry must be released rather than linger for the next cold start, and the
        // cancellation must still propagate (P2-2).
        leaseStore.cancelOnAcquire = true
        val engine = engine()

        var thrown: Throwable? = null
        try {
            engine.startMockMode(sessionId = 1L)
        } catch (e: Throwable) {
            thrown = e
        }

        assertTrue(thrown is CancellationException)
        assertEquals(setOf("gps"), leaseStore.acquired.first().second)
        assertEquals(setOf("gps"), leaseStore.releases.flatten().toSet())
        assertTrue(leaseStore.releaseUnderLock)
    }

    @Test
    fun `stop releases only the providers confirmed removed`() = runTest {
        val engine = engine()
        engine.startMockMode(sessionId = 1L)

        controller.removeFailures["network"] = SecurityException("mock app revoked")

        val results = engine.stopMockMode()

        val gps = results.single { it.provider == "gps" }
        val network = results.single { it.provider == "network" }
        assertTrue(gps.removed)
        assertFalse(network.removed)
        assertNotNull(network.reason)
        // The lease must keep network so the next cold start retries it.
        assertEquals(listOf(setOf("gps")), leaseStore.releases)
        assertTrue(leaseStore.releaseUnderLock)

        // A second stop retries only the provider that was not confirmed removed.
        controller.removeFailures.clear()
        val retry = engine.stopMockMode()
        assertEquals(listOf("network"), retry.map { it.provider })
        assertEquals(listOf(setOf("gps"), setOf("network")), leaseStore.releases)
    }

    @Test
    fun `a failed lease release keeps the providers tracked for a retry`() = runTest {
        val engine = engine()
        engine.startMockMode(sessionId = 1L)
        leaseStore.failRelease = true

        val first = engine.stopMockMode()
        assertTrue(first.all { it.removed })
        // The removal happened, but the lease could not record it: the providers stay tracked so a
        // later stop retries the release instead of forgetting them.
        assertEquals(emptyList<Set<String>>(), leaseStore.releases)

        leaseStore.failRelease = false
        val second = engine.stopMockMode()
        assertEquals(listOf("gps", "network"), second.map { it.provider })
        assertEquals(listOf(setOf("gps", "network")), leaseStore.releases)
    }

    @Test
    fun `clearStaleProviders treats absent providers as removed and keeps security failures`() = runTest {
        controller.removeFailures["gps"] = IllegalArgumentException("not registered")
        controller.removeFailures["network"] = SecurityException("not the mock app")
        val engine = engine()

        val results = engine.clearStaleProviders(listOf("gps", "network"))

        assertTrue(results.single { it.provider == "gps" }.removed)
        assertFalse(results.single { it.provider == "network" }.removed)
    }

    @Test
    fun `all rejected pushes attempt a bounded re-registration and recover`() = runTest {
        val engine = engine()
        engine.startMockMode(sessionId = 1L)
        controller.locationFailures += listOf("gps", "network")
        val addsBeforePush = log.count { it.startsWith("add:") }

        assertEquals(EnginePushOutcome.AllRejected, engine.push(fix))
        // A fresh AppOp check runs before re-registration.
        assertEquals(1, permissionChecker.checks)
        // Remove + add + enable for both providers.
        assertEquals(addsBeforePush + 2, log.count { it.startsWith("add:") })

        // Once the providers are back, the next push is delivered to both.
        controller.locationFailures.clear()
        assertEquals(EnginePushOutcome.Ok, engine.push(fix))
        assertEquals(2, controller.locationsDelivered)
    }

    @Test
    fun `re-registration is skipped when the mock AppOp was revoked`() = runTest {
        val engine = engine()
        engine.startMockMode(sessionId = 1L)
        controller.locationFailures += listOf("gps", "network")
        permissionChecker.allowed = false
        val addsBeforePush = log.count { it.startsWith("add:") }

        assertEquals(EnginePushOutcome.AllRejected, engine.push(fix))

        assertEquals(1, permissionChecker.checks)
        assertEquals(addsBeforePush, log.count { it.startsWith("add:") })
    }

    @Test
    fun `push before start rejects without touching the controller`() = runTest {
        val engine = engine()

        assertEquals(EnginePushOutcome.AllRejected, engine.push(fix))

        assertEquals(0, controller.locationsDelivered)
        assertTrue(log.isEmpty())
    }

    private class CountingPermissionChecker(var allowed: Boolean = true) : MockPermissionChecker {
        var checks = 0

        override fun canMockLocation(): Boolean {
            checks++
            return allowed
        }
    }

    private class FakeProviderController(private val log: MutableList<String>) : MockProviderController {
        val addFailures = mutableSetOf<String>()
        val enableFailures = mutableSetOf<String>()
        val removeFailures = mutableMapOf<String, Exception>()
        val locationFailures = mutableSetOf<String>()
        val added = mutableSetOf<String>()
        var locationsDelivered = 0

        override fun addProvider(provider: String) {
            if (provider in addFailures) throw IllegalArgumentException("provider busy")
            log += "add:$provider"
            added += provider
        }

        override fun setProviderEnabled(provider: String, enabled: Boolean) {
            if (enabled && provider in enableFailures) throw SecurityException("not allowed")
            log += "enable:$enabled:$provider"
        }

        override fun removeProvider(provider: String) {
            removeFailures[provider]?.let { throw it }
            log += "remove:$provider"
            added -= provider
        }

        override fun setProviderLocation(provider: String, fix: Fix) {
            if (provider in locationFailures) throw IllegalArgumentException("provider missing")
            locationsDelivered++
        }
    }

    private class RecordingLeaseStore(private val log: MutableList<String>) : ProviderLeaseStore {
        val acquired = mutableListOf<Pair<Long, Set<String>>>()
        val releases = mutableListOf<Set<String>>()
        var failAcquire = false
        var failAcquireOn: String? = null
        var failRelease = false
        var cancelOnAcquire = false

        private var lockDepth = 0
        var acquireUnderLock = true
        var releaseUnderLock = true

        override suspend fun <T> withLeaseLock(block: suspend () -> T): T {
            lockDepth++
            try {
                return block()
            } finally {
                lockDepth--
            }
        }

        override suspend fun peek(): ProviderLease? = null

        override suspend fun acquire(sessionId: Long, providers: Collection<String>): Boolean {
            acquireUnderLock = acquireUnderLock && lockDepth > 0
            if (failAcquire || providers.contains(failAcquireOn)) return false
            acquired += sessionId to providers.toSet()
            providers.forEach { log += "acquire:$it" }
            if (cancelOnAcquire) throw CancellationException("cancelled after commit")
            return true
        }

        override suspend fun release(providers: Collection<String>): Boolean {
            releaseUnderLock = releaseUnderLock && lockDepth > 0
            if (failRelease) return false
            releases += providers.toSet()
            providers.forEach { log += "release:$it" }
            return true
        }
    }
}
