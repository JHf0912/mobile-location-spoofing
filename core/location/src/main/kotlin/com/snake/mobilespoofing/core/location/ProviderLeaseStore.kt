package com.snake.mobilespoofing.core.location

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A record of the `LocationManager` test providers this app may have registered with the system.
 *
 * The lease is a *heuristic*, not proof of ownership: provider names such as `gps` and `network`
 * are global, so another mock-location app may have registered under the same name. Its only job is
 * to survive a process death (which leaves test providers live in `system_server`) so the next cold
 * start knows what to try to remove.
 *
 * [startedAtEpochMs] is wall-clock and diagnostic only. Session timing uses `elapsedRealtime`.
 */
data class ProviderLease(
    val sessionId: Long,
    val startedAtEpochMs: Long,
    val providers: Set<String>,
    val packageName: String,
)

/**
 * Persists the provider lease.
 *
 * [withLeaseLock] is a non-reentrant process-wide mutex over the *storage* only. Keep the critical
 * section short — a lease read or write — and never hold it across provider registration or a
 * service start. `Mutex` is not reentrant, so nesting [withLeaseLock] inside itself deadlocks.
 */
interface ProviderLeaseStore {
    suspend fun <T> withLeaseLock(block: suspend () -> T): T

    /** Reads the lease without modifying it. */
    suspend fun peek(): ProviderLease?

    /**
     * Adds providers to the lease and updates the session id before their provider is registered.
     *
     * Returns false when the durable write failed ([SharedPreferences.Editor.commit], never
     * `apply`). The caller must not register the provider in that case: the whole point of the
     * lease is to survive a process death, and an in-memory-only write does not.
     */
    suspend fun acquire(sessionId: Long, providers: Collection<String>): Boolean

    /**
     * Removes only the named providers; clears the lease once none remain.
     *
     * Returns false when the durable write failed. The lease then stays on disk and the next cold
     * start retries it; the caller must not treat the providers as forgotten.
     */
    suspend fun release(providers: Collection<String>): Boolean
}

/**
 * SharedPreferences-backed [ProviderLeaseStore].
 *
 * Every write runs on [Dispatchers.IO] and uses `commit()`, because the process may be killed
 * between a successful `addTestProvider` and the next flush. `apply()` would acknowledge before the
 * value reaches disk and the provider would be left untracked.
 */
@Singleton
class SharedPreferencesProviderLeaseStore internal constructor(
    private val prefs: SharedPreferences,
    private val packageName: String,
    private val ioDispatcher: CoroutineDispatcher,
) : ProviderLeaseStore {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        context.packageName,
        Dispatchers.IO,
    )

    private val mutex = Mutex()

    override suspend fun <T> withLeaseLock(block: suspend () -> T): T = mutex.withLock { block() }

    override suspend fun peek(): ProviderLease? = withContext(ioDispatcher) { read() }

    override suspend fun acquire(sessionId: Long, providers: Collection<String>): Boolean =
        withContext(ioDispatcher) {
            val existing = read()
            val merged = existing?.providers.orEmpty() + providers
            val startedAt = if (existing?.sessionId == sessionId) {
                existing.startedAtEpochMs
            } else {
                System.currentTimeMillis()
            }
            prefs.edit()
                .putLong(KEY_SESSION_ID, sessionId)
                .putLong(KEY_STARTED_AT, startedAt)
                .putStringSet(KEY_PROVIDERS, merged)
                .putString(KEY_PACKAGE, packageName)
                // commit(), not apply(): the caller registers a system-wide provider right after
                // this returns, and it may be killed before an apply() reaches disk.
                .commit()
        }

    override suspend fun release(providers: Collection<String>): Boolean {
        return withContext(ioDispatcher) {
            val existing = read() ?: return@withContext true
            val remaining = existing.providers - providers.toSet()
            val editor = prefs.edit()
            if (remaining.isEmpty()) {
                editor.clear()
            } else {
                editor.putStringSet(KEY_PROVIDERS, remaining)
            }
            // commit(), not apply(): the caller is about to treat the providers as removed, and a
            // process death before the value reaches disk would keep a lease that no longer
            // reflects reality.
            editor.commit()
        }
    }

    private fun read(): ProviderLease? {
        val sessionId = prefs.getLong(KEY_SESSION_ID, NO_SESSION)
        if (sessionId == NO_SESSION) return null
        // getStringSet returns the live instance on some implementations; copy before exposing it.
        val providers = prefs.getStringSet(KEY_PROVIDERS, null)?.toSet().orEmpty()
        if (providers.isEmpty()) return null
        val leasePackage = prefs.getString(KEY_PACKAGE, null) ?: return null
        return ProviderLease(
            sessionId = sessionId,
            startedAtEpochMs = prefs.getLong(KEY_STARTED_AT, 0L),
            providers = providers,
            packageName = leasePackage,
        )
    }

    private companion object {
        const val PREFS_NAME = "mock_provider_lease"
        const val KEY_SESSION_ID = "sessionId"
        const val KEY_STARTED_AT = "startedAtEpochMs"
        const val KEY_PROVIDERS = "providers"
        const val KEY_PACKAGE = "packageName"
        const val NO_SESSION = Long.MIN_VALUE
    }
}
