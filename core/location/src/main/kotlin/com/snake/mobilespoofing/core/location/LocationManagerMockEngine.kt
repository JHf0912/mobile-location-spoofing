package com.snake.mobilespoofing.core.location

import android.location.LocationManager
import android.util.Log
import com.snake.mobilespoofing.core.model.Fix
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Injects through `LocationManager` test providers.
 *
 * This is the engine that covers apps reading location through `LocationManager` directly rather
 * than through Google's fused client — a gap that made the tool silently do nothing for them.
 *
 * ⚠️ Test providers are registered with the *system*, not with this process. If the app is killed
 * without `stopMockMode`, the device keeps reporting a mock position to every app on it. The
 * persisted [ProviderLeaseStore] plus the cold-start cleanup are the only recovery for that case;
 * every path that can leave this class must therefore go through [stopMockMode].
 *
 * The lease is written for a provider before it is added, so a process death between the two
 * cannot leave an untracked provider behind. That write is the only part of the critical section
 * that holds [ProviderLeaseStore.withLeaseLock] — the `LocationManager` call itself is not nested
 * inside the lock, because the lock is not reentrant.
 */
@Singleton
class LocationManagerMockEngine @Inject constructor(
    private val providerController: MockProviderController,
    private val permissionChecker: MockPermissionChecker,
    private val leaseStore: ProviderLeaseStore,
) : MockLocationEngine {

    /**
     * Providers that `addProvider` succeeded on and that therefore may be registered with the
     * system. Added *before* enabling, so a failure in `setProviderEnabled` cannot leave an
     * untracked provider behind.
     */
    private val touched = mutableSetOf<String>()

    /** Providers that are registered and enabled, i.e. the ones a fix can be pushed to. */
    private val enabled = mutableSetOf<String>()

    override suspend fun startMockMode(sessionId: Long): EngineStartOutcome {
        val problems = mutableListOf<String>()
        var leaseFailure: String? = null

        for (provider in PROVIDERS) {
            // Always remove first. A provider left over from a previous run — or from a crash —
            // makes addTestProvider throw, and this is the only place that can clear it.
            removeProviderQuietly(provider)

            // Lease-before-add: the durable write must succeed before the provider exists
            // system-wide. The lock covers only the write, never the registration below.
            val leased = try {
                leaseStore.withLeaseLock {
                    leaseStore.acquire(sessionId, listOf(provider))
                }
            } catch (e: CancellationException) {
                // P2-2: cancellation can be observed after acquire's commit wrote the lease but
                // before it returned. Release just this entry (a no-op if it was never written)
                // and rethrow — the release must not mask the cancellation.
                releaseLeaseQuietly(listOf(provider))
                throw e
            } catch (e: Exception) {
                leaseFailure = "lease write failed: ${e.message}"
                break
            }
            if (!leased) {
                // I1: no durable lease, no provider registration. Anything already registered by
                // this start is rolled back below.
                leaseFailure = "lease write failed"
                break
            }

            // `registered` is true only once the provider is tracked in `touched`, i.e. addProvider
            // succeeded. Any other exit — a failed addProvider or a cancellation in this window —
            // leaves the lease written above with no backing provider, so the `finally` releases
            // just this entry (core-7 / P2-2). The success path never releases here.
            var registered = false
            try {
                providerController.addProvider(provider)
                // Track immediately: from here on the provider exists system-wide, even if the
                // enable call below fails. Teardown must remove it either way.
                touched += provider
                registered = true
                providerController.setProviderEnabled(provider, true)
                enabled += provider
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                // The app is not the system's selected mock-location app.
                problems += "$provider: not selected as the mock location app"
            } catch (e: IllegalArgumentException) {
                // The provider name is already taken, or is not one this device supports.
                problems += "$provider: provider unavailable (${e.message})"
            } catch (e: Exception) {
                problems += "$provider: ${e::class.simpleName}: ${e.message}"
            } finally {
                if (!registered) releaseLeaseQuietly(listOf(provider))
            }
        }

        if (leaseFailure != null) {
            // Do not leave a provider registered without the durable lease that would let a later
            // process find it. Providers this start already registered are removed now.
            removeTouchedProviders()
            return EngineStartOutcome.Failed(leaseFailure)
        }

        // `enabled` rather than `touched`: a provider that was added but could not be enabled
        // cannot accept fixes, so a session with only those must fail rather than pretend to run.
        return if (enabled.isEmpty()) {
            // Those providers were registered (addProvider succeeded), so a failed start must not
            // leave a registered-but-disabled provider behind. Roll them back and release their
            // lease, exactly like the lease-failure path.
            removeTouchedProviders()
            EngineStartOutcome.Failed(problems.joinToString("; "))
        } else {
            EngineStartOutcome.Ok
        }
    }

    override suspend fun push(fix: Fix): EnginePushOutcome {
        if (touched.isEmpty()) return EnginePushOutcome.AllRejected

        // A single failed push is a warning, not a session failure — one provider being flaky must
        // not stop the others from moving. Any delivery counts as success for this engine.
        if (pushToEnabled(fix)) return EnginePushOutcome.Ok

        // Every enabled provider rejected the fix (or none is enabled any more). A provider can
        // be removed or taken over by another mock app at any time; try to re-register once with a
        // bounded backoff, then deliver the current fix if that recovered anything.
        reregisterProviders(touched.toList())
        return if (pushToEnabled(fix)) EnginePushOutcome.Ok else EnginePushOutcome.AllRejected
    }

    override suspend fun stopMockMode(): List<ProviderStopResult> = removeTouchedProviders()

    /**
     * Removes every provider this engine may have registered and releases the lease for the ones
     * confirmed gone.
     *
     * `touched`, not `enabled`: a provider that was added but never enabled still exists in the
     * system and must be removed. Runs even when [startMockMode] only partially succeeded, which
     * is exactly when a leftover provider is most likely.
     */
    private suspend fun removeTouchedProviders(): List<ProviderStopResult> {
        val results = touched.toList().map { provider -> stopProvider(provider) }
        val removed = results.filter { it.removed }.map { it.provider }

        // A stopped session must not keep pushing, regardless of whether the lease write succeeds.
        enabled.clear()
        if (removed.isEmpty()) return results

        // Only a confirmed removal may clear the lease. SecurityException means the provider may
        // still be registered, so the lease has to survive for the next cold start to retry.
        val released = leaseStore.withLeaseLock {
            leaseStore.release(removed)
        }
        if (released) {
            touched.removeAll(removed.toSet())
        }
        // If the release commit failed, keep the providers in `touched`: the lease still names
        // them on disk, so a later stop (or the next cold start) must retry the release.
        return results
    }

    /**
     * Releases the lease for providers that were acquired but never registered.
     *
     * Called when `addProvider` fails: the durable lease was written first (lease-before-add), but
     * no provider backs it, so the entry must not survive to the next cold start. A failed release
     * commit leaves the lease on disk for the next cold start to retry — best effort, never a
     * start failure.
     */
    private suspend fun releaseLeaseQuietly(providers: Collection<String>) {
        // NonCancellable: this is also reached from cancellation paths (P2-2), where the coroutine
        // is already cancelled and a plain `withLeaseLock` would rethrow before the durable write.
        // The lease must be released regardless, and this helper must never throw — rethrowing here
        // would mask the original failure/cancellation.
        withContext(NonCancellable) {
            try {
                leaseStore.withLeaseLock {
                    leaseStore.release(providers)
                }
            } catch (e: Exception) {
                // Best effort: a failed release keeps the lease on disk for the next cold start to
                // retry. Log it so a leaked lease is diagnosable without changing the start outcome.
                Log.w(TAG, "failed to release lease for $providers: ${e.message}", e)
            }
        }
    }

    /**
     * Removes providers recorded by a previous process without touching any engine state.
     *
     * Called by the cold-start cleanup. Does **not** release the lease itself: the caller decides,
     * based on [ProviderStopResult.removed], which providers may be forgotten.
     */
    suspend fun clearStaleProviders(providers: Collection<String>): List<ProviderStopResult> {
        val results = providers.map { provider -> stopProvider(provider) }
        val removed = results.filter { it.removed }.map { it.provider }.toSet()
        touched.removeAll(removed)
        enabled.removeAll(removed)
        return results
    }

    // ---------------------------------------------------------------- push & re-registration

    private fun pushToEnabled(fix: Fix): Boolean {
        val providers = enabled.toList()
        if (providers.isEmpty()) return false

        var delivered = 0
        providers.forEach { provider ->
            try {
                providerController.setProviderLocation(provider, fix)
                delivered++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Partial failure is tolerated; only "nothing was delivered" is reported.
            }
        }
        return delivered > 0
    }

    /**
     * Re-registers the providers that were lost, bounded to fit inside the composite's per-push
     * budget.
     *
     * The AppOp is re-checked first: if it was revoked, re-registering would only produce a storm
     * of failed binder calls and each tick would report a failure anyway. The runner terminates the
     * session after five consecutive failed pushes.
     *
     * The 100ms/500ms backoff steps are used; the design's 2s step is deliberately omitted because
     * a third retry after 2s would outlive the 1.5s per-push timeout and be cancelled mid-way,
     * leaving the bookkeeping half-applied.
     */
    private suspend fun reregisterProviders(targets: List<String>) {
        if (targets.isEmpty() || !permissionChecker.canMockLocation()) return

        repeat(REREGISTRATION_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(REREGISTRATION_BACKOFF_MS[attempt - 1])

            val recovered = mutableSetOf<String>()
            targets.forEach { provider ->
                try {
                    removeProviderQuietly(provider)
                    providerController.addProvider(provider)
                    providerController.setProviderEnabled(provider, true)
                    recovered += provider
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Try again after the backoff while attempts remain.
                }
            }

            enabled.clear()
            enabled.addAll(recovered)
            if (recovered.size == targets.size) return
        }
    }

    // ---------------------------------------------------------------- provider plumbing

    private fun stopProvider(provider: String): ProviderStopResult {
        setProviderEnabledQuietly(provider)
        return try {
            providerController.removeProvider(provider)
            ProviderStopResult(provider, removed = true, reason = null)
        } catch (e: IllegalArgumentException) {
            // Nothing registered under that name, which is the desired state anyway.
            ProviderStopResult(provider, removed = true, reason = "already absent")
        } catch (e: SecurityException) {
            // Permission was revoked mid-session. The provider may remain until the user grants it
            // again or reboots; the lease stays so a later cold start retries.
            ProviderStopResult(provider, removed = false, reason = "security: ${e.message}")
        } catch (e: Exception) {
            ProviderStopResult(provider, removed = false, reason = e.message ?: e::class.simpleName)
        }
    }

    private fun removeProviderQuietly(provider: String) {
        try {
            providerController.removeProvider(provider)
        } catch (_: IllegalArgumentException) {
            // Nothing registered under that name, which is the desired state anyway.
        } catch (_: SecurityException) {
            // Permission was revoked mid-session. Letting this abort the loop would leave the other
            // provider even harder to clean up.
        } catch (_: Exception) {
            // Best effort; the add below reports the failure if the provider is still occupied.
        }
    }

    private fun setProviderEnabledQuietly(provider: String) {
        try {
            providerController.setProviderEnabled(provider, false)
        } catch (_: IllegalArgumentException) {
            // Already gone.
        } catch (_: SecurityException) {
            // Permission was revoked mid-session; removal below still does what it can.
        } catch (_: Exception) {
            // Best effort.
        }
    }

    companion object {
        /**
         * GPS and network both, because apps differ in which they ask for and mocking only one
         * leaves the other reporting the real position.
         */
        private val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )

        private const val REREGISTRATION_ATTEMPTS = 3
        private val REREGISTRATION_BACKOFF_MS = longArrayOf(100L, 500L)

        private const val TAG = "LocationManagerMockEngine"
    }
}
