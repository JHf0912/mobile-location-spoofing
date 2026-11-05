package com.snake.mobilespoofing.ui

import androidx.lifecycle.SavedStateHandle
import com.snake.mobilespoofing.core.geo.EndBehavior
import com.snake.mobilespoofing.core.geo.TravelSpeeds
import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor snapshot is what survives process death: mode, selected point, route, speed and end
 * behavior. These tests pin the SavedStateHandle encoding and the forgiving decode rules.
 */
class EditorStateTest {

    private val beijing = GeoPoint(39.9042, 116.4074, CoordType.WGS84)
    private val shanghai = GeoPoint(31.2304, 121.4737, CoordType.WGS84)

    @Test
    fun `round trips every editor field through SavedStateHandle`() {
        val snapshot = EditorSnapshot(
            mode = SimulationMode.ROUTE,
            selectedPoint = beijing,
            routeWaypoints = listOf(beijing, shanghai),
            travelSpeed = TravelSpeeds.CYCLING,
            endBehavior = EndBehavior.LOOP,
        )
        val handle = SavedStateHandle()

        snapshot.writeTo(handle)

        assertEquals(snapshot, readEditorSnapshot(handle))
    }

    @Test
    fun `an empty handle restores the editor defaults`() {
        assertEquals(EditorSnapshot(), readEditorSnapshot(SavedStateHandle()))
    }

    @Test
    fun `removing the selected point clears its saved coordinates`() {
        val handle = SavedStateHandle()
        EditorSnapshot(selectedPoint = beijing).writeTo(handle)
        EditorSnapshot(selectedPoint = null).writeTo(handle)

        assertNull(readEditorSnapshot(handle).selectedPoint)
        assertNull(handle.get<Double>(EditorStateKeys.SELECTED_LATITUDE))
        assertNull(handle.get<Double>(EditorStateKeys.SELECTED_LONGITUDE))
    }

    @Test
    fun `unknown enum names fall back to the defaults`() {
        val handle = SavedStateHandle(
            mapOf(
                EditorStateKeys.MODE to "NOT_A_MODE",
                EditorStateKeys.TRAVEL_SPEED to "TELEPORT",
                EditorStateKeys.END_BEHAVIOR to "EXPLODE",
            ),
        )

        assertEquals(EditorSnapshot(), readEditorSnapshot(handle))
    }

    @Test
    fun `an out-of-range saved point is discarded`() {
        val handle = SavedStateHandle(
            mapOf(
                EditorStateKeys.SELECTED_LATITUDE to 91.0,
                EditorStateKeys.SELECTED_LONGITUDE to 0.0,
            ),
        )

        assertNull(readEditorSnapshot(handle).selectedPoint)
    }

    @Test
    fun `a non-finite saved point is discarded`() {
        listOf(
            Double.NaN to 116.4074,
            39.9042 to Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY to 116.4074,
        ).forEach { (latitude, longitude) ->
            val handle = SavedStateHandle(
                mapOf(
                    EditorStateKeys.SELECTED_LATITUDE to latitude,
                    EditorStateKeys.SELECTED_LONGITUDE to longitude,
                ),
            )

            assertNull(
                "lat=$latitude lon=$longitude must not restore",
                readEditorSnapshot(handle).selectedPoint,
            )
        }
    }

    @Test
    fun `wrongly typed saved values degrade to defaults instead of throwing`() {
        // SavedStateHandle.get performs an unchecked cast, so a direct typed read would throw
        // ClassCastException at the assignment site. Each key below holds an acceptable saved
        // type that is wrong for the editor field.
        val handle = SavedStateHandle(
            mapOf(
                EditorStateKeys.MODE to 42,
                EditorStateKeys.TRAVEL_SPEED to doubleArrayOf(1.0),
                EditorStateKeys.END_BEHAVIOR to true,
                EditorStateKeys.SELECTED_LATITUDE to "not-a-double",
                EditorStateKeys.SELECTED_LONGITUDE to 116.4074,
                EditorStateKeys.ROUTE to "not-an-array",
            ),
        )

        assertEquals(EditorSnapshot(), readEditorSnapshot(handle))
    }

    @Test
    fun `route waypoints are stored as flat WGS84 latitude-longitude pairs`() {
        val flat = encodeRouteWaypoints(listOf(beijing, shanghai))

        assertEquals(4, flat.size)
        assertEquals(beijing.latitude, flat[0], 0.0)
        assertEquals(beijing.longitude, flat[1], 0.0)
        assertEquals(shanghai.latitude, flat[2], 0.0)
        assertEquals(shanghai.longitude, flat[3], 0.0)

        val decoded = decodeRouteWaypoints(flat)
        assertEquals(listOf(beijing, shanghai), decoded)
        assertTrue(decoded.all { it.coordType == CoordType.WGS84 })
    }

    @Test
    fun `the route is capped at the saved-state limit on encode and decode`() {
        val points = List(EditorSnapshot.MAX_ROUTE_WAYPOINTS + 25) { index ->
            GeoPoint(1.0 + index * 0.001, 2.0 + index * 0.001, CoordType.WGS84)
        }

        val flat = encodeRouteWaypoints(points)

        assertEquals(EditorSnapshot.MAX_ROUTE_WAYPOINTS * 2, flat.size)
        assertEquals(EditorSnapshot.MAX_ROUTE_WAYPOINTS, decodeRouteWaypoints(flat).size)
    }

    @Test
    fun `appending at the route cap is reported instead of silently dropped`() {
        val full = List(EditorSnapshot.MAX_ROUTE_WAYPOINTS) { index ->
            GeoPoint(1.0 + index * 0.001, 2.0 + index * 0.001, CoordType.WGS84)
        }

        assertEquals(RouteAppendResult.Full, appendRouteWaypoint(full, beijing))

        val almostFull = full.dropLast(1)
        assertEquals(
            RouteAppendResult.Appended(almostFull + beijing),
            appendRouteWaypoint(almostFull, beijing),
        )
    }

    @Test
    fun `an odd trailing coordinate is ignored and invalid pairs are dropped`() {
        val odd = doubleArrayOf(10.0, 20.0, 3.0)
        assertEquals(listOf(GeoPoint(10.0, 20.0, CoordType.WGS84)), decodeRouteWaypoints(odd))

        val invalid = doubleArrayOf(10.0, 20.0, Double.NaN, 30.0, 40.0, 200.0)
        assertEquals(listOf(GeoPoint(10.0, 20.0, CoordType.WGS84)), decodeRouteWaypoints(invalid))
    }

    @Test
    fun `an absent or single-value route decodes to empty`() {
        assertEquals(emptyList<GeoPoint>(), decodeRouteWaypoints(null))
        assertEquals(emptyList<GeoPoint>(), decodeRouteWaypoints(doubleArrayOf(1.0)))
    }

    @Test
    fun `snapshot from a state copies only editor fields`() {
        val state = MainUiState(
            mode = SimulationMode.ROUTE,
            selectedPoint = beijing,
            routeWaypoints = listOf(beijing, shanghai),
            travelSpeed = TravelSpeeds.DRIVING,
            endBehavior = EndBehavior.PING_PONG,
            favorites = listOf(),
            permissionGranted = true,
            notificationsGranted = true,
        )

        val snapshot = state.toEditorSnapshot()
        val restored = snapshot.applyTo(MainUiState())

        assertEquals(SimulationMode.ROUTE, restored.mode)
        assertEquals(beijing, restored.selectedPoint)
        assertEquals(listOf(beijing, shanghai), restored.routeWaypoints)
        assertEquals(TravelSpeeds.DRIVING, restored.travelSpeed)
        assertEquals(EndBehavior.PING_PONG, restored.endBehavior)
        // Session/favorites/capability state is not part of the snapshot.
        assertTrue(restored.favorites.isEmpty())
    }
}
