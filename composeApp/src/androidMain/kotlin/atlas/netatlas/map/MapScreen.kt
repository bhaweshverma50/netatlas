package atlas.netatlas.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import atlas.map.BoundingBox
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

private const val SOURCE_ID = "hexes"
private const val FILL_LAYER_ID = "hex-fill"
private const val LINE_LAYER_ID = "hex-outline"

/** Free MapLibre demo vector tiles — no API key required. */
private const val STYLE_URL = "https://demotiles.maplibre.org/style.json"

private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""

// Bengaluru.
private val INITIAL_TARGET = LatLng(12.9716, 77.5946)
private const val INITIAL_ZOOM = 11.0

/**
 * A full-screen MapLibre map that renders the coverage heatmap.
 *
 * [geoJson] is a GeoJSON `FeatureCollection` of hexagon polygons (each carrying a
 * `coverageClass` and `confidence`); the fill layer colors hexagons by coverage and scales
 * opacity by confidence. Whenever [geoJson] changes the source is updated in place. On every
 * camera-idle the visible bounds are reported through [onBoundsChanged] so the caller can
 * refetch — this is what drives the [atlas.map.MapViewModel].
 */
@Composable
fun MapScreen(
    geoJson: String,
    onBoundsChanged: (BoundingBox) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Keep the latest callback without re-creating the MapView when it changes.
    val currentOnBoundsChanged by rememberUpdatedState(onBoundsChanged)

    // MapLibre must be initialised before any MapView is inflated.
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }

    // The Style, captured once it's ready, so the geoJson effect can update the source.
    val styleHolder = remember { StyleHolder() }

    // Drive the MapView lifecycle off the composition's lifecycle.
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = {
            mapView.getMapAsync { map ->
                map.cameraPosition = CameraPosition.Builder()
                    .target(INITIAL_TARGET)
                    .zoom(INITIAL_ZOOM)
                    .build()

                map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                    setupHexLayers(style)
                    styleHolder.style = style
                    // Render whatever GeoJSON is current the moment the style is ready.
                    (style.getSource(SOURCE_ID) as? GeoJsonSource)?.setGeoJson(styleHolder.pendingGeoJson)
                }

                map.addOnCameraIdleListener {
                    currentOnBoundsChanged(map.visibleBoundingBox())
                }
            }
            mapView
        },
        modifier = modifier,
    )

    // Push new GeoJSON into the source whenever it changes (after the style is ready).
    LaunchedEffect(geoJson) {
        styleHolder.pendingGeoJson = geoJson
        (styleHolder.style?.getSource(SOURCE_ID) as? GeoJsonSource)?.setGeoJson(geoJson)
    }
}

/** Mutable holder so the style + the latest GeoJSON survive across recompositions. */
private class StyleHolder {
    var style: Style? = null
    var pendingGeoJson: String = EMPTY_FC
}

/** Installs the GeoJSON source, the coverage-colored fill layer, and a thin outline. */
private fun setupHexLayers(style: Style) {
    style.addSource(GeoJsonSource(SOURCE_ID, EMPTY_FC))

    val fillLayer = FillLayer(FILL_LAYER_ID, SOURCE_ID).withProperties(
        // Color hexagons by coverage class.
        PropertyFactory.fillColor(
            match(
                get("coverageClass"),
                color(android.graphics.Color.parseColor("#9e9e9e")), // default / unknown
                stop(literal("EXCELLENT"), color(android.graphics.Color.parseColor("#1a9850"))),
                stop(literal("GOOD"), color(android.graphics.Color.parseColor("#91cf60"))),
                stop(literal("FAIR"), color(android.graphics.Color.parseColor("#fee08b"))),
                stop(literal("POOR"), color(android.graphics.Color.parseColor("#fc8d59"))),
                stop(literal("NO_SIGNAL"), color(android.graphics.Color.parseColor("#d73027"))),
            ),
        ),
        // Fade by confidence: low confidence -> nearly transparent.
        PropertyFactory.fillOpacity(
            interpolate(
                linear(), get("confidence"),
                stop(0.0, literal(0.15f)),
                stop(1.0, literal(0.7f)),
            ),
        ),
    )

    val lineLayer = LineLayer(LINE_LAYER_ID, SOURCE_ID).withProperties(
        PropertyFactory.lineColor(android.graphics.Color.parseColor("#37474f")),
        PropertyFactory.lineWidth(0.6f),
        PropertyFactory.lineOpacity(0.5f),
    )

    style.addLayer(fillLayer)
    style.addLayer(lineLayer)
}

/** Visible map window as our [BoundingBox] (lng/lat order matches the backend query). */
private fun MapLibreMap.visibleBoundingBox(): BoundingBox {
    val bounds = projection.visibleRegion.latLngBounds
    return BoundingBox(
        minLng = bounds.longitudeWest,
        minLat = bounds.latitudeSouth,
        maxLng = bounds.longitudeEast,
        maxLat = bounds.latitudeNorth,
    )
}
