package com.swmansion.kmpmaps.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Marker
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberMarkerState
import kotlinx.coroutines.yield

/**
 * A safe version of [com.google.maps.android.compose.MarkerComposable] that captures
 * [IllegalStateException] during rasterization (when the ComposeView is measured to have 0 size)
 * and retries in the next frame instead of crashing the app.
 * [MAP-A-MARKER-BITMAP-IS-MEASURED-BEFORE-IT-EXISTS-001]
 */
@Composable
public fun SafeMarkerComposable(
    key: String? = null,
    state: MarkerState = rememberMarkerState(),
    alpha: Float = 1.0f,
    anchor: Offset = Offset(0.5f, 1.0f),
    draggable: Boolean = false,
    flat: Boolean = false,
    icon: BitmapDescriptor? = null,
    rotation: Float = 0.0f,
    snippet: String? = null,
    tag: Any? = null,
    title: String? = null,
    visible: Boolean = true,
    zIndex: Float = 0.0f,
    onClick: (Marker) -> Boolean = { false },
    onInfoWindowClick: (Marker) -> Unit = {},
    onInfoWindowClose: (Marker) -> Unit = {},
    onInfoWindowLongClick: (Marker) -> Unit = {},
    onMarkerDrag: (Marker) -> Unit = {},
    onMarkerDragEnd: (Marker) -> Unit = {},
    onMarkerDragStart: (Marker) -> Unit = {},
    content: @Composable () -> Unit
) {
    val safeIcon = rememberSafeComposeBitmapDescriptor(key ?: "", content = content)
    Marker(
        state = state,
        alpha = alpha,
        anchor = anchor,
        draggable = draggable,
        flat = flat,
        icon = safeIcon ?: icon,
        rotation = rotation,
        snippet = snippet,
        tag = tag,
        title = title,
        visible = visible,
        zIndex = zIndex,
        onClick = onClick,
        onInfoWindowClick = onInfoWindowClick,
        onInfoWindowClose = onInfoWindowClose,
        onInfoWindowLongClick = onInfoWindowLongClick,
        onMarkerDrag = onMarkerDrag,
        onMarkerDragEnd = onMarkerDragEnd,
        onMarkerDragStart = onMarkerDragStart
    )
}

@Composable
internal fun rememberSafeComposeBitmapDescriptor(
    vararg keys: Any?,
    content: @Composable () -> Unit,
): BitmapDescriptor? {
    val parent = LocalView.current as ViewGroup
    val compositionContext = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)

    var descriptor by remember(parent, compositionContext, *keys) {
        mutableStateOf<BitmapDescriptor?>(
            try {
                renderSafeComposableToBitmapDescriptor(parent, compositionContext, currentContent)
            } catch (e: IllegalStateException) {
                null
            }
        )
    }

    if (descriptor == null) {
        LaunchedEffect(parent, compositionContext, *keys) {
            yield() // Wait for next frame
            try {
                descriptor = renderSafeComposableToBitmapDescriptor(parent, compositionContext, currentContent)
            } catch (e: IllegalStateException) {
                Log.w("KMPMaps", "Still failed to render marker after yield: ${e.message}")
            }
        }
    }

    return descriptor
}

private val measureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

private fun renderSafeComposableToBitmapDescriptor(
    parent: ViewGroup,
    compositionContext: CompositionContext,
    content: @Composable () -> Unit,
): BitmapDescriptor {
    val composeView = ComposeView(parent.context)
        .apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setParentCompositionContext(compositionContext)
            setContent(content)
        }
        .also(parent::addView)

    try {
        composeView.measure(measureSpec, measureSpec)

        if (composeView.measuredWidth == 0 || composeView.measuredHeight == 0) {
            throw IllegalStateException(
                "The ComposeView was measured to have a width or height of " +
                "zero. Make sure that the content has a non-zero size."
            )
        }

        composeView.layout(0, 0, composeView.measuredWidth, composeView.measuredHeight)

        val bitmap = Bitmap.createBitmap(
            composeView.measuredWidth,
            composeView.measuredHeight,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        composeView.draw(canvas)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    } finally {
        parent.removeView(composeView)
    }
}
