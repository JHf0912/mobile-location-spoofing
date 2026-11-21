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
 * is exercised only through this seam. The production [FIX_TIMEOUT_MS] / [TOTAL_FIX_TIMEOUT_MS]
 * constants are referenced directly so the tests track their values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CurrentLocationSourceTest {

    @Test
    fun `returns the first non-null result without waiting for later providers`() = runTest {
        val calls = mutableListOf<String>()

        val result = async {
            firstWithinBudgets(
                providers = listOf("gps", "network"),
                perProviderTimeoutMs = FIX_TIMEOUT_MS,
                totalTimeoutMs = TOTAL_FIX_TIMEOUT_MS,
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
                perProviderTimeoutMs = FIX_TIMEOUT_MS,
                totalTimeoutMs = TOTAL_FIX_TIMEOUT_MS,
                acquire = hang,
            )
        }
        advanceUntilIdle()

        assertNull(result.await())
        // The first provider may consume its full per-provider budget, but the second gets only the
        // remainder: the whole attempt must end at the total budget, not at 2 × the per-provider.
        assertEquals(TOTAL_FIX_TIMEOUT_MS, testScheduler.currentTime)
    }
}
