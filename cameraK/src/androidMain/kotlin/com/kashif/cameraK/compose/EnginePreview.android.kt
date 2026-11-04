@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.compose

import android.content.Context
import androidx.camera.core.SurfaceRequest
import androidx.camera.view.PreviewView
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.utils.CameraKEngineApi

/**
 * A FIT_CENTER PreviewView whose surface provider is attached to the engine's Preview while this is
 * composed. The camera opens from the session, not from here: closing the attachment on dispose
 * clears the provider only if it is still the current one, so an incoming preview keeps its surface.
 *
 * In inspection mode (Android Studio previews, layoutlib snapshots) there is no camera and
 * PreviewView cannot be built, so only the overlay is drawn.
 */
@Composable
internal actual fun EnginePreview(
    source: PlatformPreviewSource,
    deviceOrientation: DeviceOrientation,
    modifier: Modifier,
    overlay: (@Composable CameraPreviewScope.() -> Unit)?,
) {
    Box(modifier = modifier) {
        if (!LocalInspectionMode.current) AttachedPreviewView(source, Modifier.matchParentSize())
        if (overlay != null) CameraPreviewOverlay(deviceOrientation, overlay)
    }
}

@Composable
private fun AttachedPreviewView(source: PlatformPreviewSource, modifier: Modifier) {
    val context = LocalContext.current
    val previewView = remember(context) { fitCenterPreviewView(context) }
    // The host view is attached to a window by the time effects run; its display sets the ViewPort.
    val hostView = LocalView.current

    DisposableEffect(source, previewView) {
        val attachment = source.attach(previewView.surfaceProvider, hostView.display)
        onDispose { attachment.close() }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

// FIT_CENTER shows the whole frame (letterboxed) instead of cropping it to fill the view, so the
// preview matches the captured field of view. Built outside remember's lambda because Compose lint's
// RememberReturnType reads an apply block that ends in an assignment as remember returning Unit.
private fun fitCenterPreviewView(context: Context): PreviewView =
    PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }

/**
 * The engine's preview in CameraXViewfinder, with [overlay] over it. [viewfinder] renders each
 * SurfaceRequest the engine publishes; [coordinateTransformer] maps the viewfinder's coordinates to the
 * camera frame's.
 */
@Composable
internal fun ViewfinderPreview(
    source: PlatformPreviewSource,
    deviceOrientation: DeviceOrientation,
    modifier: Modifier,
    overlay: (@Composable CameraPreviewScope.() -> Unit)?,
    coordinateTransformer: MutableCoordinateTransformer = remember { MutableCoordinateTransformer() },
    viewfinder: @Composable (SurfaceRequest, MutableCoordinateTransformer, Modifier) -> Unit = { _, _, _ -> },
) {
    Box(modifier = modifier) {
        if (overlay != null) CameraPreviewOverlay(deviceOrientation, overlay)
    }
}
