package com.snake.mobilespoofing.core.data

import com.snake.mobilespoofing.core.model.CoordType
import org.junit.Assert.assertEquals
import org.junit.Test

class CoordTypeParsingTest {

    @Test
    fun `known datum is preserved`() {
        assertEquals(CoordType.GCJ02, parseCoordType("GCJ02"))
        assertEquals(CoordType.WGS84, parseCoordType("WGS84"))
    }

    @Test
    fun `unknown datum falls back to the database canonical datum`() {
        // Rows are written as WGS84. The old fallback was GCJ02, which shifted a corrupt row by
        // hundreds of metres when it was injected.
        assertEquals(CoordType.WGS84, parseCoordType("something-else"))
        assertEquals(CoordType.WGS84, parseCoordType(null))
    }
}
