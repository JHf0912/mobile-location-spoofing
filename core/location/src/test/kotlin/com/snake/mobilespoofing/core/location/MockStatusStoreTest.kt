package com.snake.mobilespoofing.core.location

import app.cash.turbine.test
import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint
import com.snake.mobilespoofing.core.model.SessionErrorReason
import com.snake.mobilespoofing.core.model.SessionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockStatusStoreTest {

    private val point = GeoPoint(39.9042, 116.4074, CoordType.WGS84)

    @Test
    fun `emits session states in order`() = runTest {
        val store = MockStatusStore()
        store.state.test {
            assertTrue(awaitItem() is SessionState.Idle)

            store.emit(SessionState.Starting(sessionId = 1L, startedAtElapsedMs = 10L))
            assertTrue(awaitItem() is SessionState.Starting)

            store.emit(SessionState.Running(1L, 10L, point))
            assertEquals(SessionState.Running(1L, 10L, point), awaitItem())

            store.emit(SessionState.Error(sessionId = 1L, reason = SessionErrorReason.EngineStartFailed))
            assertTrue(awaitItem() is SessionState.Error)

            store.emit(SessionState.Idle)
            assertTrue(awaitItem() is SessionState.Idle)
        }
    }

    @Test
    fun `a late collector immediately sees the existing session epoch`() = runTest {
        // This is what a recreated ViewModel does: there is no per-tick emission to wait for, so
        // the current Running (with its constant epoch) must already be the StateFlow value.
        val store = MockStatusStore()
        store.emit(SessionState.Running(sessionId = 7L, sessionEpochElapsedMs = 42L, lastPointWgs84 = point))

        store.state.test {
            val running = awaitItem() as SessionState.Running
            assertEquals(7L, running.sessionId)
            assertEquals(42L, running.sessionEpochElapsedMs)
            assertEquals(point, running.lastPointWgs84)
        }
    }
}
