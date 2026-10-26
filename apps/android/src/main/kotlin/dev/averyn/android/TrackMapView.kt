package dev.averyn.android

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.averyn.client.TrackMap
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

private const val TRACK_SOURCE = "track"
private const val TRACK_LAYER = "track-line"

/** The padding around the track when the camera fits it, in pixels. */
private const val CAMERA_PADDING_PX = 48

/**
 * A plain background: what the line is drawn on when the style of the server cannot be loaded (offline). The base
 * map needs the network (offline tiles are MVP-3, TDD-0012); the track does not, it comes from the copy.
 */
private const val OFFLINE_STYLE =
    """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#eceff1"}}]}"""

/**
 * The track of an activity on a map (TDD-0012): one line per recording interval, so a pause is a break, with the
 * camera fitted to it. The map is a picture, not a tool: its gestures are off, so that it does not trap the
 * scrolling of the screen it sits in.
 *
 * [styleUrl] is the style of the server (`mapStyleUrl`): whoever serves it sees the area looked at.
 */
@Composable
internal fun TrackMapView(
    track: TrackMap,
    styleUrl: String,
) {
    val context = LocalContext.current
    val lifecycle = (LocalActivity.current as ComponentActivity).lifecycle
    val mapView =
        remember {
            MapLibre.getInstance(context)
            MapView(context)
        }
    var offline by remember { mutableStateOf(false) }

    // The map view has to follow the lifecycle of the screen: it owns a renderer and a connection.
    DisposableEffect(mapView, lifecycle) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView, track, styleUrl) {
        offline = false
        mapView.getMapAsync { map ->
            map.uiSettings.setAllGesturesEnabled(false)
            map.uiSettings.isCompassEnabled = false
            var drawn = false

            fun draw(style: Style) {
                if (drawn) return
                drawn = true
                style.addSource(GeoJsonSource(TRACK_SOURCE, track.geoJson))
                style.addLayer(
                    LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(
                        PropertyFactory.lineColor("#e4572e"),
                        PropertyFactory.lineWidth(4f),
                        PropertyFactory.lineCap("round"),
                        PropertyFactory.lineJoin("round"),
                    ),
                )
                fitCamera(map, track)
            }
            // The style of the server could not be read (no network): the track is still drawn, on a plain
            // background, and the screen says so.
            mapView.addOnDidFailLoadingMapListener {
                if (!drawn) {
                    offline = true
                    map.setStyle(Style.Builder().fromJson(OFFLINE_STYLE)) { draw(it) }
                }
            }
            map.setStyle(styleUrl) { draw(it) }
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxWidth().height(240.dp),
    )
    if (offline) Text("The base map is not available offline; the track is.")
}

private fun fitCamera(
    map: MapLibreMap,
    track: TrackMap,
) {
    val bounds =
        LatLngBounds
            .Builder()
            .include(LatLng(track.minLatitude, track.minLongitude))
            .include(LatLng(track.maxLatitude, track.maxLongitude))
            .build()
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, CAMERA_PADDING_PX))
}
