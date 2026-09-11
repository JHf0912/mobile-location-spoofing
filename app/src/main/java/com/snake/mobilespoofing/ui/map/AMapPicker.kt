package com.snake.mobilespoofing.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.snake.mobilespoofing.core.geo.CoordinateTransformer
import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint

/**
 * AMap renders GCJ02, so every position is converted on the way in and on the way out.
 *
 * Taps are reported as [LatLng] in GCJ02 — the caller converts, so there is exactly one place that
 * knows about the datum.
 */
@Composable
fun AMapPicker(
    waypointsWgs84: List<GeoPoint>,
    selectedPointWgs84: GeoPoint?,
    onPointClick: (LatLng) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // The click listener is registered once on the MapView, so it would otherwise capture the
    // first lambda and keep calling a stale one forever.
    val currentOnPointClick by rememberUpdatedState(onPointClick)

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            map.uiSettings.isZoomControlsEnabled = false
            map.setOnMapClickListener { latLng -> currentOnPointClick(latLng) }
        }
    }

    val markers = remember { mutableListOf<Marker>() }
    val polyline = remember { mutableListOf<Polyline>() }

    // Markers are removed and re-added rather than cleared with map.clear(): clear() also drops the
    // camera position and anything else the SDK is holding, which shows up as a visible flicker on
    // every waypoint added.
    LaunchedEffect(waypointsWgs84, selectedPointWgs84) {
        val map = mapView.map
        markers.forEach { it.remove() }
        markers.clear()
        polyline.forEach { it.remove() }
        polyline.clear()

        val routeLatLngs = waypointsWgs84.map { it.toGcjLatLng() }

        if (routeLatLngs.size >= 2) {
            map.addPolyline(
                PolylineOptions()
                    .addAll(routeLatLngs)
                    .width(10f),
            )?.let(polyline::add)
        }

        routeLatLngs.forEach { position ->
            map.addMarker(MarkerOptions().position(position))?.let(markers::add)
        }

        selectedPointWgs84?.let { point ->
            map.addMarker(MarkerOptions().position(point.toGcjLatLng()))?.let(markers::add)
            // Only chase the camera for a single point; a route is edited with the map already in
            // view, and moving it on every tap is disorienting.
            if (waypointsWgs84.isEmpty()) {
                map.moveCamera(
                    com.amap.api.maps.CameraUpdateFactory.newLatLngZoom(point.toGcjLatLng(), 15f),
                )
            }
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDestroy()
        }
    }

    AndroidView(modifier = modifier, factory = { mapView })
}

private fun GeoPoint.toGcjLatLng(): LatLng {
    val gcj = CoordinateTransformer.fromWgs84(this, CoordType.GCJ02)
    return LatLng(gcj.latitude, gcj.longitude)
}
