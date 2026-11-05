package com.snake.mobilespoofing.core.location

import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SharedPreferences-backed lease store is tested against a JVM fake of the platform interface,
 * so the merge/release semantics, the durable-write contract and the lock run without Robolectric.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderLeaseStoreTest {

    private val prefs = FakeSharedPreferences()

    private fun TestScope.store() = SharedPreferencesProviderLeaseStore(
        prefs = prefs,
        packageName = PACKAGE,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    @Test
    fun `acquire persists a lease with the package and a wall-clock start time`() = runTest {
        val store = store()
        val before = System.currentTimeMillis()

        assertTrue(store.acquire(sessionId = 7L, providers = listOf("gps")))

        val lease = store.peek()
        requireNotNull(lease)
        assertEquals(7L, lease.sessionId)
        assertEquals(setOf("gps"), lease.providers)
        assertEquals(PACKAGE, lease.packageName)
        assertTrue(lease.startedAtEpochMs >= before)
        // F1/F10: the store must use commit(), not apply(), so a process death cannot lose the
        // write that the provider registration depends on.
        assertEquals(0, prefs.applyCalls)
        assertTrue(prefs.commitCalls > 0)
    }

    @Test
    fun `acquire reports a failed durable write and does not persist`() = runTest {
        val store = store()
        prefs.commitResult = false

        assertFalse(store.acquire(sessionId = 7L, providers = listOf("gps")))

        // The in-memory fake behaves like a failed disk write: nothing is readable, so the engine
        // must not add the provider (tested against the engine as well).
        assertNull(store.peek())
        assertEquals(0, prefs.applyCalls)
    }

    @Test
    fun `release reports a failed durable write and leaves the lease`() = runTest {
        val store = store()
        assertTrue(store.acquire(sessionId = 7L, providers = listOf("gps")))
        prefs.commitResult = false

        assertFalse(store.release(listOf("gps")))

        assertEquals(setOf("gps"), store.peek()?.providers)
    }

    @Test
    fun `acquire accumulates providers for the same session`() = runTest {
        val store = store()

        store.acquire(sessionId = 7L, providers = listOf("gps"))
        store.acquire(sessionId = 7L, providers = listOf("network"))

        assertEquals(setOf("gps", "network"), store.peek()?.providers)
    }

    @Test
    fun `a new session updates the id and keeps unconfirmed providers`() = runTest {
        val store = store()
        store.acquire(sessionId = 1L, providers = listOf("gps"))

        store.acquire(sessionId = 2L, providers = listOf("network"))

        val lease = store.peek()
        requireNotNull(lease)
        assertEquals(2L, lease.sessionId)
        // gps may still exist from the old session; it must stay tracked until removal is
        // confirmed, otherwise a failed teardown would be forgotten.
        assertEquals(setOf("gps", "network"), lease.providers)
    }

    @Test
    fun `release removes only the named providers and clears the lease when empty`() = runTest {
        val store = store()
        store.acquire(sessionId = 7L, providers = listOf("gps", "network"))

        assertTrue(store.release(listOf("gps")))
        assertEquals(setOf("network"), store.peek()?.providers)

        assertTrue(store.release(listOf("network")))
        assertNull(store.peek())
    }

    @Test
    fun `release of a provider that is not leased is a no-op`() = runTest {
        val store = store()
        store.acquire(sessionId = 7L, providers = listOf("gps"))

        assertTrue(store.release(listOf("network")))

        assertEquals(setOf("gps"), store.peek()?.providers)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `peek returns a copy that cannot corrupt the stored lease`() = runTest {
        val store = store()
        store.acquire(sessionId = 7L, providers = listOf("gps", "network"))

        val lease = requireNotNull(store.peek())
        // If peek returned the live preference set, this would corrupt the lease.
        (lease.providers as? MutableSet<String>)?.add("extra")

        assertEquals(setOf("gps", "network"), store.peek()?.providers)
    }

    @Test
    fun `withLeaseLock serialises concurrent blocks`() = runTest {
        val store = store()
        val order = mutableListOf<String>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = launch {
            store.withLeaseLock {
                order += "first-enter"
                releaseFirst.await()
                order += "first-exit"
            }
        }
        runCurrent()

        val second = launch {
            store.withLeaseLock { order += "second" }
        }
        runCurrent()
        // The second block must not run while the first still holds the mutex.
        assertEquals(listOf("first-enter"), order)

        releaseFirst.complete(Unit)
        runCurrent()
        assertEquals(listOf("first-enter", "first-exit", "second"), order)
        first.join()
        second.join()
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        var commitResult = true
        var commitCalls = 0
        var applyCalls = 0

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            values[key] as? MutableSet<String> ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                putValue(key, value)

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor = putValue(key, values?.toMutableSet())

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
                putValue(key, value)

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
                putValue(key, value)

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
                putValue(key, value)

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
                putValue(key, value)

            private fun putValue(key: String?, value: Any?): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) removals += key
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }

            override fun commit(): Boolean {
                commitCalls++
                if (!commitResult) return false
                if (clearAll) values.clear()
                removals.forEach(values::remove)
                values.putAll(pending)
                return true
            }

            override fun apply() {
                // Deliberately does not apply: a store that used apply() instead of commit() would
                // lose the write here, and the tests assert applyCalls == 0.
                applyCalls++
            }
        }
    }

    private companion object {
        const val PACKAGE = "com.snake.mobilespoofing"
    }
}
