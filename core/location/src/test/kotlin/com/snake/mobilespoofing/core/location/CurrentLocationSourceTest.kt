package com.snake.mobilespoofing.core.location

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The fresh-fix budget logic is tested through [firstWithinBudgets], which carries no Android
 * types; [AndroidCurrentLocationSource] itself still needs a real `LocationManager`, so its wiring
 * is exercised only through this seam. The 8s/10s literals mirror the production FIX_TIMEOUT_MS /
 * TOTAL_FIX_TIMEOUT_MS constants.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CurrentLocationSourceTest {

    @Test
    fun `returns the first non-null result without waiting for later providers`() = runTest {
        val calls = mutableListOf<String>()

        val result = async {
            firstWithinBudgets(
                providers = listOf("gps", "network"),
                perProviderTimeoutMs = 8_000L,
                totalTimeoutMs = 10_000L,
            ) { provider ->
                calls += provider
                if (provider == "gps") "gps-fix" else null
            }
        }
        advanceUntilIdle()

        assertEquals("gps-fix", result.await())
        assertEquals(listOf("gps"), calls)
    }

    @Test
    fun `the total budget caps the wait across providers`() = runTest {
        val hang: suspend (String) -> String? = { awaitCancellation() }

        val result = async {
            firstWithinBudgets(
                providers = listOf("gps", "network"),
                perProviderTimeoutMs = 8_000L,
                totalTimeoutMs = 10_000L,
                acquire = hang,
            )
        }
        advanceUntilIdle()

        assertNull(result.await())
        // The first provider may consume its full 8s, but the second gets only the remaining 2s:
        // the whole attempt must end at the total budget, not at 2 × 8s = 16s.
        assertEquals(10_000L, testScheduler.currentTime)
    }
}
