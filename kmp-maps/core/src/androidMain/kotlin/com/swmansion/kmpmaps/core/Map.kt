package com.swmansion.kmpmaps.core

import android.Manifest
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapsComposeExperimentalApi
import kotlinx.coroutines.flow.collectLatest
import com.google.maps.android.compose.Marker
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.clustering.Clustering
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.data.Layer
import com.google.maps.android.data.geojson.GeoJsonLayer as GoogleGeoJsonLayer

/** Duration a stable-id marker glides between successive coordinates. [DRIVE-PUCK-NATIVE-001] */
private const val MARKER_GLIDE_MS = 1000

/**
 * Renders a [LiveMarker]. Its [LiveMarker.position] is observed in a coroutine (snapshotFlow) and the
 * marker glides to each new value NATIVELY — no composable recomposes when it moves, so the caller's
 * map is never rebuilt under a moving marker. [LiveMarker.rotation] is read here, recomposing ONLY this
 * isolated node. [DRIVE-PUCK-NATIVE-001]
 */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
private fun LiveMarkerNode(
    live: LiveMarker,
    customMarkerContent: Map<String, @Composable (Marker) -> Unit>,
    onMarkerClick: ((Marker) -> Unit)?,
) {
    val markerState = remember { MarkerState(live.position.value.toGoogleMapsLatLng()) }
    LaunchedEffect(Unit) {
        snapshotFlow { live.position.value }.collectLatest { target ->
            val end = target.toGoogleMapsLatLng()
            val start = markerState.position
            if (start.latitude == end.latitude && start.longitude == end.longitude) return@collectLatest
            Animatable(0f).animateTo(1f, tween(MARKER_GLIDE_MS, easing = LinearEasing)) {
                markerState.position = LatLng(
                    start.latitude + (end.latitude - start.latitude) * value,
                    start.longitude + (end.longitude - start.longitude) * value,
                )
            }
        }
    }
    val content = customMarkerContent[live.contentId] ?: return
    // Stable placeholder Marker for the content/click callback (live-marker content ignores position).
    val marker = remember(live.id) { Marker(coordinates = live.position.value, title = null, contentId = live.contentId, id = live.id) }
    SafeMarkerComposable(
        live.contentId ?: live.id,
        state = markerState,
        anchor = live.androidMarkerOptions.anchor.toOffset(),
        zIndex = live.androidMarkerOptions.zIndex ?: 0.0f,
        rotation = live.rotation.value,
        flat = live.androidMarkerOptions.flat,
        onClick = {
            onMarkerClick?.invoke(marker)
            onMarkerClick == null
        },
        content = { content(marker) },
    )
}

/** Android implementation of the Map composable using Google Maps. */
@OptIn(ExperimentalPermissionsApi::class, MapsComposeExperimentalApi::class)
@Composable
public actual fun Map(
    modifier: Modifier,
    cameraPosition: CameraPosition?,
    properties: MapProperties,
    uiSettings: MapUISettings,
    clusterSettings: ClusterSettings,
    markers: List<Marker>,
    liveMarkers: List<LiveMarker>,
    circles: List<Circle>,
    polygons: List<Polygon>,
    polylines: List<Polyline>,
    onCameraMove: ((CameraPosition) -> Unit)?,
    onMarkerClick: ((Marker) -> Unit)?,
    onMarkerDragEnd: ((Marker) -> Unit)?,
    onCircleClick: ((Circle) -> Unit)?,
    onPolygonClick: ((Polygon) -> Unit)?,
    onPolylineClick: ((Polyline) -> Unit)?,
    onMapClick: ((Coordinates) -> Unit)?,
    onMapLongClick: ((Coordinates) -> Unit)?,
    onPOIClick: ((Coordinates) -> Unit)?,
    onMapLoaded: (() -> Unit)?,
    geoJsonLayers: List<GeoJsonLayer>,
    customMarkerContent: Map<String, @Composable (Marker) -> Unit>,
    webCustomMarkerContent: Map<String, (Marker) -> String>,
) {
    var mapLoaded by remember { mutableStateOf(false) }
    val locationPermissionState = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)

    LaunchedEffect(properties.isMyLocationEnabled) {
        if (properties.isMyLocationEnabled && !locationPermissionState.status.isGranted) {
            locationPermissionState.launchPermissionRequest()
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val viewportWidthPx = with(density) { maxWidth.roundToPx() }
        val viewportHeightPx = with(density) { maxHeight.roundToPx() }

        val cameraPositionState = rememberCameraPositionState {
            cameraPosition?.let {
                position = it.toGoogleMapsCameraPosition(viewportWidthPx, viewportHeightPx)
            }
        }

        // Apply camera updates from a single long-lived collector instead of a LaunchedEffect keyed on
        // cameraPosition: continuous following passes a new cameraPosition every frame, which cancelled
        // and relaunched the effect 60×/s and applied move() with irregular timing (visible stutter).
        // snapshotFlow reacts only to actual changes, from one coroutine. [DRIVE-PUCK-NATIVE-001]
        val currentCameraPosition by rememberUpdatedState(cameraPosition)
        LaunchedEffect(mapLoaded) {
            if (!mapLoaded) return@LaunchedEffect
            snapshotFlow { currentCameraPosition }
                .collect { pos -> pos?.let { cameraPositionState.move(it.toCameraUpdate()) } }
        }

        GoogleMap(
            mapColorScheme = properties.mapTheme.toGoogleMapsTheme(),
            modifier = modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = properties.toGoogleMapsProperties(locationPermissionState),
            uiSettings = uiSettings.toGoogleMapsUiSettings(),
            onMapClick =
                onMapClick?.let { callback ->
                    { latLng -> callback(Coordinates(latLng.latitude, latLng.longitude)) }
                },
            onMapLongClick =
                onMapLongClick?.let { callback ->
                    { latLng -> callback(Coordinates(latLng.latitude, latLng.longitude)) }
                },
            onPOIClick =
                onPOIClick?.let { callback ->
                    { poi -> callback(Coordinates(poi.latLng.latitude, poi.latLng.longitude)) }
                },
            onMapLoaded = {
                mapLoaded = true
                onMapLoaded?.invoke()
            },
        ) {
            MapEffect(properties.contentPadding) { map ->
                properties.contentPadding?.run {
                    map.setPadding(start.toInt(), top.toInt(), end.toInt(), bottom.toInt())
                }
            }

            var androidGeoJsonLayers by remember {
                mutableStateOf<Map<Int, GoogleGeoJsonLayer>>(emptyMap())
            }

            var geoJsonExtractedMarkers by remember {
                mutableStateOf<Map<Int, List<Marker>>>(emptyMap())
            }

            MapEffect(geoJsonLayers) { map ->
                runCatching {
                        val desiredKeys = geoJsonLayers.indices.toSet()
                        val keysToRemove = androidGeoJsonLayers.keys - desiredKeys
                        keysToRemove.forEach { k -> androidGeoJsonLayers[k]?.removeLayerFromMap() }

                        androidGeoJsonLayers =
                            androidGeoJsonLayers.filterKeys(desiredKeys::contains)
                        geoJsonExtractedMarkers =
                            geoJsonExtractedMarkers.filterKeys(desiredKeys::contains)

                        geoJsonLayers.forEachIndexed { index, geo ->
                            if (geo.visible == false) {
                                androidGeoJsonLayers[index]?.removeLayerFromMap()
                                androidGeoJsonLayers = androidGeoJsonLayers - index
                                geoJsonExtractedMarkers = geoJsonExtractedMarkers - index
                                return@forEachIndexed
                            }

                            androidGeoJsonLayers[index]?.removeLayerFromMap()

                            map.renderGeoJsonLayer(geo, clusterSettings, onMarkerClick)?.let {
                                androidGeoJsonLayers = androidGeoJsonLayers + (index to it.layer)
                                geoJsonExtractedMarkers =
                                    geoJsonExtractedMarkers + (index to it.extractedMarkers)
                            }
                        }
                    }
                    .onFailure { t -> Log.e("KMPMaps", "Failed to render GeoJSON layers", t) }
            }

            DisposableEffect(Unit) {
                onDispose { androidGeoJsonLayers.values.forEach(Layer::removeLayerFromMap) }
            }

            if (clusterSettings.enabled) {
                val clusterItems =
                    remember(markers, geoJsonExtractedMarkers) {
                        (markers + geoJsonExtractedMarkers.values.flatten()).map(
                            ::MarkerClusterItem
                        )
                    }

                Clustering(
                    items = clusterItems,
                    onClusterClick = { androidCluster ->
                        clusterSettings.onClusterClick?.invoke(androidCluster.toNativeCluster())
                            ?: false
                    },
                    onClusterItemClick = { clusterItem ->
                        onMarkerClick?.invoke(clusterItem.marker)
                        onMarkerClick == null
                    },
                    clusterContent = { androidCluster ->
                        if (clusterSettings.clusterContent != null) {
                            clusterSettings.clusterContent.invoke(androidCluster.toNativeCluster())
                        } else {
                            DefaultCluster(size = androidCluster.size)
                        }
                    },
                    clusterItemContent = { clusterItem ->
                        customMarkerContent[clusterItem.marker.contentId]?.invoke(
                            clusterItem.marker
                        ) ?: DefaultPin(clusterItem.marker)
                    },
                )
            } else {
                markers.forEach { marker ->
                    key(marker.getId()) {
                        val markerState =
                            remember(marker.getId()) {
                                MarkerState(marker.coordinates.toGoogleMapsLatLng())
                            }

                        // Move the marker to each new coordinate. A stable-id marker GLIDES natively: the
                        // animation runs in this coroutine and only writes MarkerState.position, which
                        // maps-compose applies to the native marker via its node — NOT by recomposing the
                        // Compose tree. So a continuously-moving marker (a live driving puck) never forces
                        // per-frame recomposition of the caller, which was saturating the main thread and
                        // starving touch input / stuttering pans. Callers pass raw fixes (~1 Hz) and get a
                        // smooth glide for free. Markers without a stable id snap (previous behaviour).
                        // [DRIVE-PUCK-NATIVE-001]
                        val target = marker.coordinates.toGoogleMapsLatLng()
                        LaunchedEffect(target) {
                            val start = markerState.position
                            if (start.latitude == target.latitude && start.longitude == target.longitude) {
                                return@LaunchedEffect
                            }
                            if (marker.id != null) {
                                Animatable(0f).animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(MARKER_GLIDE_MS, easing = LinearEasing),
                                ) {
                                    markerState.position = LatLng(
                                        start.latitude + (target.latitude - start.latitude) * value,
                                        start.longitude + (target.longitude - start.longitude) * value,
                                    )
                                }
                            } else {
                                markerState.position = target
                            }
                        }

                        val content = customMarkerContent[marker.contentId]

                        if (marker.androidMarkerOptions.draggable) {
                            LaunchedEffect(markerState.isDragging) {
                                if (!markerState.isDragging) {
                                    marker.coordinates = markerState.position.toCoordinates()
                                    onMarkerDragEnd?.invoke(marker)
                                }
                            }
                        }

                        if (content != null) {
                            // Re-render the cached marker bitmap in place whenever the visual
                            // content changes (its [contentId]) — WITHOUT changing the marker's
                            // identity ([getId]). This lets a stable-id marker update its glyph
                            // (e.g. a driving puck's baked heading) without being disposed and
                            // recreated, which is what caused flicker on moving markers.
                            SafeMarkerComposable(
                                marker.contentId ?: marker.getId(),
                                state = markerState,
                                title = marker.title,
                                anchor = marker.androidMarkerOptions.anchor.toOffset(),
                                draggable = marker.androidMarkerOptions.draggable,
                                snippet = marker.androidMarkerOptions.snippet,
                                zIndex = marker.androidMarkerOptions.zIndex ?: 0.0f,
                                rotation = marker.androidMarkerOptions.rotation ?: 0.0f,
                                flat = marker.androidMarkerOptions.flat,
                                onClick = {
                                    onMarkerClick?.invoke(marker)
                                    onMarkerClick == null
                                },
                                content = { content(marker) },
                            )
                        } else {
                            Marker(
                                state = markerState,
                                title = marker.title,
                                anchor = marker.androidMarkerOptions.anchor.toOffset(),
                                draggable = marker.androidMarkerOptions.draggable,
                                snippet = marker.androidMarkerOptions.snippet,
                                zIndex = marker.androidMarkerOptions.zIndex ?: 0.0f,
                                rotation = marker.androidMarkerOptions.rotation ?: 0.0f,
                                flat = marker.androidMarkerOptions.flat,
                                onClick = {
                                    onMarkerClick?.invoke(marker)
                                    onMarkerClick == null
                                },
                            )
                        }
                    }
                }
            }

            liveMarkers.forEach { live ->
                key(live.id) {
                    LiveMarkerNode(live, customMarkerContent, onMarkerClick)
                }
            }

            circles.forEach { circle ->
                Circle(
                    center = circle.center.toGoogleMapsLatLng(),
                    radius = circle.radius.toDouble(),
                    strokeColor = Color(circle.lineColor?.toArgb() ?: android.graphics.Color.BLACK),
                    strokeWidth = circle.lineWidth ?: 10f,
                    fillColor = Color(circle.color?.toArgb() ?: android.graphics.Color.TRANSPARENT),
                    clickable = true,
                    onClick = {
                        if (onCircleClick != null) {
                            onCircleClick(circle)
                        } else {
                            onMapClick?.invoke(circle.center)
                        }
                    },
                )
            }

            polygons.forEach { polygon ->
                Polygon(
                    points = polygon.coordinates.map(Coordinates::toGoogleMapsLatLng),
                    strokeColor =
                        Color(polygon.lineColor?.toArgb() ?: android.graphics.Color.BLACK),
                    strokeWidth = polygon.lineWidth,
                    fillColor =
                        Color(polygon.color?.toArgb() ?: android.graphics.Color.TRANSPARENT),
                    clickable = true,
                    onClick = {
                        if (onPolygonClick != null) {
                            onPolygonClick(polygon)
                        } else {
                            onMapClick?.invoke(polygon.coordinates[0])
                        }
                    },
                )
            }

            polylines.forEach { polyline ->
                Polyline(
                    points = polyline.coordinates.map(Coordinates::toGoogleMapsLatLng),
                    color = Color(polyline.lineColor?.toArgb() ?: android.graphics.Color.BLACK),
                    width = polyline.width,
                    clickable = true,
                    onClick = {
                        if (onPolylineClick != null) {
                            onPolylineClick(polyline)
                        } else {
                            onMapClick?.invoke(polyline.coordinates[0])
                        }
                    },
                )
            }

            LaunchedEffect(cameraPositionState.position) {
                val bounds = cameraPositionState.projection?.visibleRegion?.latLngBounds
                onCameraMove?.invoke(cameraPositionState.position.toCameraPosition(bounds))
            }
        }
    }
}
