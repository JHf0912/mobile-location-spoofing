package com.snake.mobilespoofing.core.location

import app.cash.turbine.test
import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint
import com.snake.mobilespoofing.core.model.MockStatus
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MockStatusStoreTest {

    @Test
    fun emits_running_then_idle() = runTest {
        val store = MockStatusStore()
        store.status.test {
            val first = awaitItem()
            assert(first is MockStatus.Idle)

            store.emit(MockStatus.Running(GeoPoint(1.0, 2.0, CoordType.WGS84), 1L))
            assert(awaitItem() is MockStatus.Running)

            store.emit(MockStatus.Idle)
            assert(awaitItem() is MockStatus.Idle)
        }
    }
}
