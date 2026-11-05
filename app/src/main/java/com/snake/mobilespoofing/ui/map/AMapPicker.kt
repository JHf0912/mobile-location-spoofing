package com.snake.mobilespoofing.ui.map

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.snake.mobilespoofing.R
import com.snake.mobilespoofing.core.geo.CoordinateTransformer
import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint
import com.snake.mobilespoofing.ui.theme.AppRadius
import com.snake.mobilespoofing.ui.theme.appColors

private const val AMAP_PRIVACY_URL = "https://lbs.amap.com/pages/privacy/"

/**
 * AMap renders GCJ02, so every position is converted on the way in and on the way out.
 *
 * Taps are reported as [LatLng] in GCJ02 — the caller converts, so there is exactly one place that
 * knows about the datum.
 *
 * The inline card is deliberately non-interactive: a `MapView` inside a scrolling page fights the
 * page for vertical drags. Tapping the preview opens [MapCard]'s full-screen picker, where the map
 * owns the gesture space.
 */
@Composable
fun AMapPicker(
    waypointsWgs84: List<GeoPoint>,
    selectedPointWgs84: GeoPoint?,
    onPointClick: (LatLng) -> Unit,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = true,
    onPreviewClick: (() -> Unit)? = null,
) {
    if (LocalInspectionMode.current) {
        MapPlaceholder(modifier, label = stringResource(R.string.map_preview_label))
        return
    }

    // AMap 10.x ships only arm64-v8a/armeabi-v7a native libraries. Check the *primary* ABI:
    // x86 emulators list arm64-v8a as a translation target, but the translated library fails later
    // on a handler thread, so a plain "any ABI matches" check is not enough.
    val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull()
    if (primaryAbi != "arm64-v8a" && primaryAbi != "armeabi-v7a") {
        MapPlaceholder(modifier, label = stringResource(R.string.map_unavailable))
        return
    }

    val context = LocalContext.current
    var agreed by remember { mutableStateOf(AMapPrivacy.isAgreed(context)) }

    if (!agreed) {
        AMapConsent(
            modifier = modifier,
            onAgree = {
                AMapPrivacy.agree(context)
                agreed = true
            },
        )
        return
    }

    AMapMap(
        waypointsWgs84 = waypointsWgs84,
        selectedPointWgs84 = selectedPointWgs84,
        onPointClick = onPointClick,
        gesturesEnabled = gesturesEnabled,
        onPreviewClick = onPreviewClick,
        modifier = modifier,
    )
}

@Composable
private fun AMapConsent(
    modifier: Modifier,
    onAgree: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.appColors

    Surface(
        modifier = modifier.padding(12.dp),
        shape = RoundedCornerShape(AppRadius.card),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.hairline),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.map_consent_title),
                style = MaterialTheme.typography.titleSmall,
                color = colors.ink,
            )
            Text(
                text = stringResource(R.string.map_consent_body),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onAgree) {
                    Text(stringResource(R.string.map_consent_agree))
                }
                TextButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AMAP_PRIVACY_URL)))
                        } catch (_: ActivityNotFoundException) {
                            // No browser; the consent text above is still shown.
                        }
                    },
                ) {
                    Text(stringResource(R.string.map_consent_policy))
                }
            }
        }
    }
}

@Composable
private fun AMapMap(
    waypointsWgs84: List<GeoPoint>,
    selectedPointWgs84: GeoPoint?,
    onPointClick: (LatLng) -> Unit,
    gesturesEnabled: Boolean,
    onPreviewClick: (() -> Unit)?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // The click listener is registered once on the MapView, so it would otherwise capture the
    // first lambda and keep calling a stale one forever.
    val currentOnPointClick by rememberUpdatedState(onPointClick)

    val mapView = remember {
        // Consent was recorded before this composable is reached, but the SDK flags are per-process.
        // runCatching, not try/catch: a missing AMap native library surfaces as
        // UnsatisfiedLinkError, which is an Error rather than an Exception.
        runCatching {
            AMapPrivacy.applyToSdk(context)
            MapView(context).apply {
                onCreate(null)
                map.uiSettings.apply {
                    isZoomControlsEnabled = false
                    isScrollGesturesEnabled = gesturesEnabled
                    isZoomGesturesEnabled = gesturesEnabled
                    isRotateGesturesEnabled = gesturesEnabled
                    isTiltGesturesEnabled = gesturesEnabled
                }
                map.setOnMapClickListener { latLng -> currentOnPointClick(latLng) }
            }
        }.getOrNull()
    }

    if (mapView == null) {
        MapPlaceholder(modifier, label = stringResource(R.string.map_unavailable))
        return
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

    DisposableEffect(lifecycleOwner, mapView) {
        var destroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> if (!destroyed) {
                    destroyed = true
                    mapView.onDestroy()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        // If the owner is already resumed, the observer will not receive a retrospective ON_RESUME.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            mapView.onResume()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!destroyed) {
                mapView.onPause()
                mapView.onDestroy()
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { mapView })

        if (!gesturesEnabled && onPreviewClick != null) {
            // The whole preview is the full-screen entry, and the MapView never competes with the
            // page for drag gestures. There used to be a small "tap the map" pill on top; it read
            // like the only tappable element and its small target made opening the picker feel
            // unreliable, so the card itself is the target now.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        onClickLabel = stringResource(R.string.map_dialog_title),
                        onClick = onPreviewClick,
                    ),
            )
        }
    }
}

/** Used by Compose previews and when the AMap native library is unavailable on this ABI. */
@Composable
private fun MapPlaceholder(modifier: Modifier, label: String) {
    val colors = MaterialTheme.appColors

    Box(
        modifier = modifier.background(colors.surfaceMuted),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val step = 32.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(
                    color = colors.hairline,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
                x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(
                    color = colors.hairline,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
                y += step
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Place,
                contentDescription = null,
                tint = colors.accentCyan,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
    }
}

private fun GeoPoint.toGcjLatLng(): LatLng {
    val gcj = CoordinateTransformer.fromWgs84(this, CoordType.GCJ02)
    return LatLng(gcj.latitude, gcj.longitude)
}
