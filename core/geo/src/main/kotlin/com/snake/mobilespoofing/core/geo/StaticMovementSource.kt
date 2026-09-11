package com.snake.mobilespoofing.core.geo

import com.snake.mobilespoofing.core.model.Fix
import com.snake.mobilespoofing.core.model.GeoPoint

/**
 * Stays at one point — the "teleport and hold" case.
 *
 * Note the output is not literally constant once realism is applied: no real receiver reports a
 * byte-identical position forever, and a perfectly frozen fix is its own kind of tell. That
 * variation is layered on by the engine rather than baked in here, so this class stays a pure
 * function of time and is trivial to test.
 */
class StaticMovementSource(private val pointWgs84: GeoPoint) : MovementSource {

    override fun fixAt(elapsedMs: Long): Fix = Fix(point = pointWgs84)
}
