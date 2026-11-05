@file:OptIn(CameraKEngineApi::class, InternalCameraKApi::class)

package com.kashif.cameraK.compose

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.SurfaceRequest
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.engine.previewAspectRatio
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.coroutines.flow.MutableStateFlow

/** The engine's preview in CameraXViewfinder, with the overlay in the preview's box. */
@Composable
internal actual fun EnginePreview(
    source: PlatformPreviewSource,
    deviceOrientation: DeviceOrientation,
    modifier: Modifier,
    overlay: (@Composable CameraPreviewScope.() -> Unit)?,
) {
    ViewfinderPreview(source, deviceOrientation, modifier, overlay)
}

/**
 * The engine's preview in CameraXViewfinder, with [overlay] over it; both Android preview composables
 * render through this (design §7.1). While composed, a surface provider is attached to [source] and
 * publishes each SurfaceRequest the engine's Preview sends, which [viewfinder] renders. The camera
 * opens from the session, not from here: closing the attachment on dispose clears the provider only if
 * it is still the current one, so an incoming preview keeps its surface.
 *
 * [coordinateTransformer] maps the viewfinder's coordinates to the camera frame's, for overlays and
 * tap-to-focus. In inspection mode (Android Studio previews, layoutlib snapshots) there is no camera,
 * so only the overlay is drawn.
 */
@Composable
internal fun ViewfinderPreview(
    source: PlatformPreviewSource,
    deviceOrientation: DeviceOrientation,
    modifier: Modifier,
    overlay: (@Composable CameraPreviewScope.() -> Unit)?,
    aspectRatio: AspectRatio? = source.previewAspectRatio(),
    coordinateTransformer: MutableCoordinateTransformer = remember { MutableCoordinateTransformer() },
    viewfinder: @Composable (SurfaceRequest, MutableCoordinateTransformer, Modifier) -> Unit =
        { request, transformer, viewfinderModifier -> FitViewfinder(request, transformer, viewfinderModifier) },
) {
    Box(modifier = modifier) {
        if (!LocalInspectionMode.current) {
            AttachedViewfinder(source, coordinateTransformer, viewfinder, Modifier.matchParentSize())
        }
        if (overlay != null) CameraPreviewOverlay(deviceOrientation, overlay)
    }
}

@Composable
private fun AttachedViewfinder(
    source: PlatformPreviewSource,
    coordinateTransformer: MutableCoordinateTransformer,
    viewfinder: @Composable (SurfaceRequest, MutableCoordinateTransformer, Modifier) -> Unit,
    modifier: Modifier,
) {
    val requests = remember(source) { MutableStateFlow<SurfaceRequest?>(null) }
    // The host view is attached to a window by the time effects run; its display sets the ViewPort.
    val hostView = LocalView.current

    DisposableEffect(source, hostView) {
        val attachment = source.attach({ request -> requests.value = request }, hostView.display)
        onDispose { attachment.close() }
    }

    val request by requests.collectAsState()
    request?.let { viewfinder(it, coordinateTransformer, modifier) }
}

// Fit shows the whole frame (letterboxed) instead of cropping it to fill the box, as 1.x's FIT_CENTER
// PreviewView did, so the preview matches the captured field of view. The implementation mode is
// CameraX's own choice for the camera, which falls back to a TextureView on devices with SurfaceView
// quirks.
@Composable
private fun FitViewfinder(
    request: SurfaceRequest,
    coordinateTransformer: MutableCoordinateTransformer,
    modifier: Modifier,
) {
    CameraXViewfinder(
        surfaceRequest = request,
        modifier = modifier,
        coordinateTransformer = coordinateTransformer,
        contentScale = ContentScale.Fit,
    )
}
