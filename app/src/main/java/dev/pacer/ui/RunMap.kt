package dev.pacer.ui

import android.content.Context
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pacer.core.TrackPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** OpenFreeMap's dark style: free, no key, no account, matches the black theme. */
const val MAP_STYLE = "https://tiles.openfreemap.org/styles/dark"

/** Max points drawn; longer tracks are thinned for display only (the recording is untouched). */
private const val MAX_DRAWN = 1500

/**
 * MapView wrapped for Compose with lifecycle forwarded, so it renders only while on screen and is
 * destroyed when it leaves composition. The position dot comes from the run's own fixes: no
 * MapLibre location component, so the map never starts a GPS request of its own.
 */
@Composable
fun RunMap(
    track: List<TrackPoint>,
    trackSize: Int,
    position: LatLng?,
    modifier: Modifier = Modifier,
    live: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var follow by remember { mutableStateOf(live) }
    val holder = remember { MapHolder(context) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> holder.view.onStart()
                Lifecycle.Event.ON_RESUME -> holder.view.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.view.onPause()
                Lifecycle.Event.ON_STOP -> holder.view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // Walk the view down through the states it reached before destroying it.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) holder.view.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) holder.view.onStop()
            holder.view.onDestroy()
        }
    }

    LaunchedEffect(trackSize, position, holder.ready, follow) {
        holder.update(track, position, follow, fitAll = !live)
    }

    Box(modifier) {
        AndroidView(factory = { holder.view }, modifier = Modifier.fillMaxSize())
        if (live) {
            FilterChip(
                selected = follow,
                onClick = { follow = !follow },
                label = { Text("Follow") },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            )
        }
    }
}

private class MapHolder(context: Context) {
    var ready by mutableStateOf(false)
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private val drawn = ArrayList<LatLng>()
    private var consumed = 0
    private var stride = 1
    private var fitted = false

    val view: MapView = run {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { m ->
                map = m
                m.uiSettings.isRotateGesturesEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                m.setStyle(Style.Builder().fromUri(MAP_STYLE)) { s ->
                    s.addSource(GeoJsonSource("route"))
                    s.addSource(GeoJsonSource("start"))
                    s.addSource(GeoJsonSource("me"))
                    s.addLayer(
                        LineLayer("route", "route").withProperties(
                            lineColor(Color.parseColor("#00E676")), lineWidth(4f),
                            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                        ),
                    )
                    s.addLayer(CircleLayer("start", "start").withProperties(circleRadius(6f), circleColor(Color.WHITE)))
                    s.addLayer(
                        CircleLayer("me", "me").withProperties(
                            circleRadius(7f), circleColor(Color.parseColor("#2979FF")),
                            circleStrokeColor(Color.WHITE), circleStrokeWidth(2f),
                        ),
                    )
                    style = s
                    ready = true
                }
            }
        }
    }

    fun update(track: List<TrackPoint>, position: LatLng?, follow: Boolean, fitAll: Boolean) {
        val s = style ?: return
        val m = map ?: return
        if (track.size < consumed) { drawn.clear(); consumed = 0; stride = 1 }
        // Append only what's new. drawn[k] is track[k * stride]; doubling the stride keeps every
        // other drawn point, so thinning never needs the full track again.
        for (i in consumed until track.size) {
            if (i % stride == 0) drawn += LatLng(track[i].lat, track[i].lon)
        }
        consumed = track.size
        if (drawn.size > MAX_DRAWN) {
            stride *= 2
            val thinned = drawn.filterIndexed { i, _ -> i % 2 == 0 }
            drawn.clear(); drawn += thinned
        }
        val line = drawn.map { Point.fromLngLat(it.longitude, it.latitude) }.toMutableList()
        track.lastOrNull()?.let { if ((track.size - 1) % stride != 0) line += Point.fromLngLat(it.lon, it.lat) }
        if (line.size >= 2) s.getSourceAs<GeoJsonSource>("route")?.setGeoJson(LineString.fromLngLats(line))
        track.firstOrNull()?.let { s.getSourceAs<GeoJsonSource>("start")?.setGeoJson(Point.fromLngLat(it.lon, it.lat)) }
        position?.let { s.getSourceAs<GeoJsonSource>("me")?.setGeoJson(Point.fromLngLat(it.longitude, it.latitude)) }

        if (fitAll) {
            if (!fitted && drawn.size >= 2) {
                fitted = true
                m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(drawn).build(), 60))
            }
        } else if (follow && position != null) {
            if (!fitted) {
                fitted = true
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(position, 16.0))
            } else {
                m.easeCamera(CameraUpdateFactory.newLatLng(position), 600)
            }
        }
    }
}
